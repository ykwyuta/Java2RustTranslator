//! Java の配列（固定長・参照で共有・添字の境界検査あり・null を取りうる）。

use crate::rt::throw;
use std::cell::RefCell;
use std::rc::Rc;

/// Java の `T[]`。`clone()` は参照の複製（同じ配列を指す）。
pub struct JArray<T>(Option<Rc<RefCell<Vec<T>>>>);

impl<T> Clone for JArray<T> {
    fn clone(&self) -> JArray<T> {
        JArray(self.0.clone())
    }
}

/// 参照型の既定値は Java と同じく null（`new int[3][]` の要素など）。
impl<T> Default for JArray<T> {
    fn default() -> JArray<T> {
        JArray(None)
    }
}

fn check_length(len: i32) {
    if len < 0 {
        throw("java.lang.NegativeArraySizeException", Some(&len.to_string()));
    }
}

impl<T> JArray<T> {
    /// `null`。
    pub const fn null() -> JArray<T> {
        JArray(None)
    }

    pub fn is_null(&self) -> bool {
        self.0.is_none()
    }

    fn cell(&self) -> &RefCell<Vec<T>> {
        match &self.0 {
            Some(c) => c,
            None => throw("java.lang.NullPointerException", None),
        }
    }

    /// 参照の同一性（Java の `a == b`）。
    pub fn ptr_eq(a: &JArray<T>, b: &JArray<T>) -> bool {
        match (&a.0, &b.0) {
            (None, None) => true,
            (Some(x), Some(y)) => Rc::ptr_eq(x, y),
            _ => false,
        }
    }

    /// `a.length`。
    pub fn length(&self) -> i32 {
        self.cell().borrow().len() as i32
    }

    /// 内部の Vec に対して操作する（ランタイム内部用）。
    pub(crate) fn with_mut<R>(&self, f: impl FnOnce(&mut Vec<T>) -> R) -> R {
        f(&mut self.cell().borrow_mut())
    }

    pub(crate) fn with_ref<R>(&self, f: impl FnOnce(&Vec<T>) -> R) -> R {
        f(&self.cell().borrow())
    }
}

impl<T: Clone> JArray<T> {
    /// 配列初期化子 `{a, b, c}`。
    pub fn from_vec(v: Vec<T>) -> JArray<T> {
        JArray(Some(Rc::new(RefCell::new(v))))
    }

    /// 要素を関数で初期化する（多次元配列 `new int[a][b]` の外側など）。
    pub fn new_with(len: i32, mut f: impl FnMut(i32) -> T) -> JArray<T> {
        check_length(len);
        JArray::from_vec((0..len).map(&mut f).collect())
    }

    fn check_index(&self, index: i32) -> usize {
        let len = self.cell().borrow().len();
        if index < 0 || index as usize >= len {
            throw(
                "java.lang.ArrayIndexOutOfBoundsException",
                Some(&format!("Index {index} out of bounds for length {len}")),
            );
        }
        index as usize
    }

    /// `a[i]`（読み出し）。
    pub fn get(&self, index: i32) -> T {
        let i = self.check_index(index);
        self.cell().borrow()[i].clone()
    }

    /// `a[i] = v`。
    pub fn set(&self, index: i32, value: T) {
        let i = self.check_index(index);
        self.cell().borrow_mut()[i] = value;
    }

    /// 中身の複製。
    pub fn to_vec(&self) -> Vec<T> {
        self.cell().borrow().clone()
    }

    /// `a.clone()`（浅いコピー）。
    pub fn clone_array(&self) -> JArray<T> {
        JArray::from_vec(self.to_vec())
    }
}

impl<T: Clone + Default> JArray<T> {
    /// `new T[len]`（要素は既定値。参照型なら null）。
    pub fn new(len: i32) -> JArray<T> {
        check_length(len);
        JArray::from_vec(vec![T::default(); len as usize])
    }
}

impl<T: Clone + 'static> From<JArray<T>> for crate::JObject {
    fn from(a: JArray<T>) -> crate::JObject {
        crate::JObject::from_array(a)
    }
}

/// `System.arraycopy(src, srcPos, dest, destPos, length)`。同じ配列内の重なりも Java と同じく正しく扱う。
pub fn arraycopy<T: Clone>(src: &JArray<T>, src_pos: i32, dest: &JArray<T>, dest_pos: i32, length: i32) {
    let (src_len, dest_len) = (src.length(), dest.length());
    if src_pos < 0 || dest_pos < 0 || length < 0
        || src_pos as i64 + length as i64 > src_len as i64
        || dest_pos as i64 + length as i64 > dest_len as i64
    {
        throw(
            "java.lang.ArrayIndexOutOfBoundsException",
            Some(&format!("arraycopy: last source index {} out of bounds for length {}", src_pos as i64 + length as i64, src_len)),
        );
    }
    let tmp: Vec<T> = src.with_ref(|v| v[src_pos as usize..(src_pos + length) as usize].to_vec());
    dest.with_mut(|v| v[dest_pos as usize..(dest_pos + length) as usize].clone_from_slice(&tmp));
}
