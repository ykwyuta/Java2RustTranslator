//! System.out / System.err / System.in。

use crate::lang::stringify::JStringify;
use std::cell::RefCell;
use std::io::{BufWriter, Stdout, Write};

thread_local! {
    static STDOUT: RefCell<BufWriter<Stdout>> = RefCell::new(BufWriter::new(std::io::stdout()));
}

/// 標準出力のバッファを書き出す（終了時・未捕捉例外時・System.err への出力前に呼ぶ）。
pub fn flush_stdout() {
    let _ = STDOUT.try_with(|o| {
        if let Ok(mut o) = o.try_borrow_mut() {
            let _ = o.flush();
        }
    });
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Target {
    Out,
    Err,
}

/// `java.io.PrintStream`（System.out / System.err のみ）。
#[derive(Clone, Copy, Debug)]
pub struct PrintStream(Target);

/// `System.out`。
pub fn system_out() -> PrintStream {
    PrintStream(Target::Out)
}

/// `System.err`。
pub fn system_err() -> PrintStream {
    PrintStream(Target::Err)
}

impl PrintStream {
    fn write_str(&self, s: &str) {
        match self.0 {
            Target::Out => STDOUT.with(|o| {
                let _ = o.borrow_mut().write_all(s.as_bytes());
            }),
            Target::Err => {
                flush_stdout();
                let _ = std::io::stderr().write_all(s.as_bytes());
            }
        }
    }

    /// `print(x)`。
    pub fn print<T: JStringify + ?Sized>(&self, v: &T) {
        let mut s = String::new();
        v.append_to(&mut s);
        self.write_str(&s);
    }

    /// `println(x)`。
    pub fn println<T: JStringify + ?Sized>(&self, v: &T) {
        let mut s = String::new();
        v.append_to(&mut s);
        s.push('\n');
        self.write_str(&s);
    }

    /// `println()`。
    pub fn newline(&self) {
        self.write_str("\n");
    }

    /// `print(char[])` / `println(char[])`。
    pub fn print_chars(&self, chars: &crate::JArray<u16>, newline: bool) -> crate::JResult<()> {
        let mut s = String::from_utf16_lossy(&chars.to_vec()?);
        if newline {
            s.push('\n');
        }
        self.write_str(&s);
        Ok(())
    }

    /// `flush()`。
    pub fn flush(&self) {
        if self.0 == Target::Out {
            flush_stdout();
        }
    }
}

/// `java.io.InputStream`（System.in のみ）。
#[derive(Clone, Copy, Debug)]
pub struct InputStream;

/// `System.in`。
pub fn system_in() -> InputStream {
    InputStream
}
