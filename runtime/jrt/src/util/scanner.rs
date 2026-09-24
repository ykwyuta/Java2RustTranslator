//! java.util.Scanner（System.in からの読み取りのみ）。

use crate::io::InputStream;
use crate::lang::string::JString;
use crate::rt::{throw, JResult};
use std::cell::RefCell;
use std::io::BufRead;
use std::rc::Rc;

#[derive(Default)]
struct State {
    /// 現在の行（改行を除く）と、その中の読み取り位置（バイト）。
    line: Option<String>,
    pos: usize,
    eof: bool,
}

/// Java の `Scanner`。空白区切りのトークンと行単位の読み取りを提供する。
#[derive(Clone, Default)]
pub struct Scanner(Rc<RefCell<State>>);

impl State {
    fn fill_line(&mut self) -> bool {
        if self.line.is_some() {
            return true;
        }
        if self.eof {
            return false;
        }
        let mut buf = String::new();
        match std::io::stdin().lock().read_line(&mut buf) {
            Ok(0) | Err(_) => {
                self.eof = true;
                false
            }
            Ok(_) => {
                if buf.ends_with('\n') {
                    buf.pop();
                    if buf.ends_with('\r') {
                        buf.pop();
                    }
                }
                self.line = Some(buf);
                self.pos = 0;
                true
            }
        }
    }

    /// 次のトークンの位置まで進める。見つからなければ false。
    fn skip_whitespace(&mut self) -> bool {
        loop {
            if !self.fill_line() {
                return false;
            }
            let line = self.line.as_ref().unwrap();
            let rest = &line[self.pos..];
            let trimmed = rest.trim_start();
            if trimmed.is_empty() {
                self.line = None;
                continue;
            }
            self.pos += rest.len() - trimmed.len();
            return true;
        }
    }

    fn next_token(&mut self) -> Option<String> {
        if !self.skip_whitespace() {
            return None;
        }
        let line = self.line.as_ref().unwrap();
        let rest = &line[self.pos..];
        let end = rest.find(char::is_whitespace).unwrap_or(rest.len());
        let token = rest[..end].to_string();
        self.pos += end;
        Some(token)
    }

    fn peek_token(&mut self) -> Option<String> {
        if !self.skip_whitespace() {
            return None;
        }
        let rest = &self.line.as_ref().unwrap()[self.pos..];
        let end = rest.find(char::is_whitespace).unwrap_or(rest.len());
        Some(rest[..end].to_string())
    }
}

fn no_such_element<T>() -> JResult<T> {
    throw("java.util.NoSuchElementException", None)
}

fn input_mismatch<T>(token: &str) -> JResult<T> {
    throw("java.util.InputMismatchException", Some(&format!("For input string: \"{token}\"")))
}

impl Scanner {
    /// `new Scanner(System.in)`。
    pub fn new(_input: InputStream) -> Scanner {
        Scanner::default()
    }

    /// `next()`。
    pub fn next(&self) -> JResult<JString> {
        let t = self.0.borrow_mut().next_token();
        match t {
            Some(t) => Ok(JString::from(t)),
            None => no_such_element(),
        }
    }

    fn token(&self) -> JResult<String> {
        let t = self.0.borrow_mut().next_token();
        match t {
            Some(t) => Ok(t),
            None => no_such_element(),
        }
    }

    /// `nextInt()`。
    pub fn next_int(&self) -> JResult<i32> {
        let t = self.token()?;
        t.parse().or_else(|_| input_mismatch(&t))
    }

    /// `nextLong()`。
    pub fn next_long(&self) -> JResult<i64> {
        let t = self.token()?;
        t.parse().or_else(|_| input_mismatch(&t))
    }

    /// `nextDouble()`。
    pub fn next_double(&self) -> JResult<f64> {
        let t = self.token()?;
        t.parse().or_else(|_| input_mismatch(&t))
    }

    /// `nextBoolean()`。
    pub fn next_boolean(&self) -> JResult<bool> {
        let t = self.token()?;
        match t.to_ascii_lowercase().as_str() {
            "true" => Ok(true),
            "false" => Ok(false),
            _ => input_mismatch(&t),
        }
    }

    /// `nextLine()`: 現在の行の残りを返し、次の行へ進む。
    pub fn next_line(&self) -> JResult<JString> {
        let mut st = self.0.borrow_mut();
        if !st.fill_line() {
            drop(st);
            return throw("java.util.NoSuchElementException", Some("No line found"));
        }
        let line = st.line.take().unwrap_or_default();
        let rest = line[st.pos..].to_string();
        Ok(JString::from(rest))
    }

    /// `hasNext()`。
    pub fn has_next(&self) -> bool {
        self.0.borrow_mut().skip_whitespace()
    }

    /// `hasNextInt()`。
    pub fn has_next_int(&self) -> bool {
        self.0.borrow_mut().peek_token().is_some_and(|t| t.parse::<i32>().is_ok())
    }

    /// `hasNextLine()`。
    pub fn has_next_line(&self) -> bool {
        self.0.borrow_mut().fill_line()
    }

    /// `close()`。
    pub fn close(&self) {}
}
