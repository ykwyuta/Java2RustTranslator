//! java.lang.StringBuilder 相当（共有参照・可変）。

use crate::lang::string::JString;
use crate::lang::stringify::JStringify;
use crate::rt::{throw, JResult};
use std::cell::RefCell;
use std::rc::Rc;

/// Java の `StringBuilder`。Java と同じく参照で共有され、内容は UTF-16 で保持する。
#[derive(Clone, Default)]
pub struct StringBuilder(Rc<RefCell<Vec<u16>>>);

impl StringBuilder {
    /// `new StringBuilder()`。
    pub fn new() -> StringBuilder {
        StringBuilder::default()
    }

    /// `new StringBuilder(int capacity)`。
    pub fn with_capacity(capacity: i32) -> JResult<StringBuilder> {
        if capacity < 0 {
            return throw("java.lang.NegativeArraySizeException", Some(&capacity.to_string()));
        }
        Ok(StringBuilder(Rc::new(RefCell::new(Vec::with_capacity(capacity as usize)))))
    }

    /// `new StringBuilder(String)`。
    pub fn from_str(s: &JString) -> JResult<StringBuilder> {
        Ok(StringBuilder(Rc::new(RefCell::new(s.as_str()?.encode_utf16().collect()))))
    }

    fn check_index(&self, index: i32, len: usize) -> JResult<()> {
        if index < 0 || index as usize >= len {
            return throw(
                "java.lang.StringIndexOutOfBoundsException",
                Some(&format!("index {index},length {len}")),
            );
        }
        Ok(())
    }

    /// `append(x)`。Java と同じく自分自身を返す。
    pub fn append<T: JStringify + ?Sized>(&self, v: &T) -> StringBuilder {
        let mut units = Vec::new();
        v.append_utf16(&mut units);
        self.0.borrow_mut().extend(units);
        self.clone()
    }

    /// `insert(offset, x)`。
    pub fn insert<T: JStringify + ?Sized>(&self, offset: i32, v: &T) -> JResult<StringBuilder> {
        let len = self.0.borrow().len();
        if offset < 0 || offset as usize > len {
            return throw("java.lang.StringIndexOutOfBoundsException", Some(&format!("offset {offset}, length {len}")));
        }
        let mut units = Vec::new();
        v.append_utf16(&mut units);
        let mut b = self.0.borrow_mut();
        let tail = b.split_off(offset as usize);
        b.extend(units);
        b.extend(tail);
        drop(b);
        Ok(self.clone())
    }

    pub fn length(&self) -> i32 {
        self.0.borrow().len() as i32
    }

    pub fn char_at(&self, index: i32) -> JResult<u16> {
        let b = self.0.borrow();
        self.check_index(index, b.len())?;
        Ok(b[index as usize])
    }

    pub fn set_char_at(&self, index: i32, c: u16) -> JResult<()> {
        let len = self.0.borrow().len();
        self.check_index(index, len)?;
        self.0.borrow_mut()[index as usize] = c;
        Ok(())
    }

    pub fn delete_char_at(&self, index: i32) -> JResult<StringBuilder> {
        let len = self.0.borrow().len();
        self.check_index(index, len)?;
        self.0.borrow_mut().remove(index as usize);
        Ok(self.clone())
    }

    pub fn set_length(&self, new_len: i32) -> JResult<()> {
        if new_len < 0 {
            return throw("java.lang.StringIndexOutOfBoundsException", Some(&format!("String index out of range: {new_len}")));
        }
        self.0.borrow_mut().resize(new_len as usize, 0);
        Ok(())
    }

    /// `reverse()`（サロゲートペアは保つ）。
    pub fn reverse(&self) -> StringBuilder {
        let mut b = self.0.borrow_mut();
        b.reverse();
        // 反転で順序が逆になったサロゲートペアを戻す。
        let mut i = 0;
        while i + 1 < b.len() {
            if (0xDC00..0xE000).contains(&b[i]) && (0xD800..0xDC00).contains(&b[i + 1]) {
                b.swap(i, i + 1);
                i += 2;
            } else {
                i += 1;
            }
        }
        drop(b);
        self.clone()
    }

    /// `toString()`。
    pub fn to_jstring(&self) -> JString {
        JString::from(String::from_utf16_lossy(&self.0.borrow()))
    }
}

impl JStringify for StringBuilder {
    fn append_to(&self, out: &mut String) {
        out.push_str(&String::from_utf16_lossy(&self.0.borrow()))
    }

    fn append_utf16(&self, out: &mut Vec<u16>) {
        // sb.append(sb) のように自分自身を追記する場合に備えて、先に複製する。
        let copy = self.0.borrow().clone();
        out.extend(copy);
    }
}
