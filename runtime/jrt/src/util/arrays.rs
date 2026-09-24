//! java.util.Arrays の static メソッド。

use crate::array::JArray;
use crate::lang::string::JString;
use crate::lang::stringify::JStringify;
use crate::rt::throw;
use std::cmp::Ordering;

/// `Arrays.sort(int[])` など（整数・char・String）。
pub fn sort<T: Ord + Clone>(a: &JArray<T>) {
    a.with_mut(|v| v.sort());
}

/// `Arrays.sort(a, from, to)`。
pub fn sort_range<T: Ord + Clone>(a: &JArray<T>, from: i32, to: i32) {
    check_range(a.length(), from, to);
    a.with_mut(|v| v[from as usize..to as usize].sort());
}

/// Java の `Double.compare` 順（-0.0 < 0.0、NaN は最大）。
pub fn java_cmp_f64(a: &f64, b: &f64) -> Ordering {
    match (a.is_nan(), b.is_nan()) {
        (true, true) => Ordering::Equal,
        (true, false) => Ordering::Greater,
        (false, true) => Ordering::Less,
        _ => a.total_cmp(b),
    }
}

/// `Arrays.sort(double[])`。
pub fn sort_f64(a: &JArray<f64>) {
    a.with_mut(|v| v.sort_by(java_cmp_f64));
}

/// `Arrays.sort(float[])`。
pub fn sort_f32(a: &JArray<f32>) {
    a.with_mut(|v| v.sort_by(|x, y| java_cmp_f64(&(*x as f64), &(*y as f64))));
}

/// `Arrays.fill(a, v)`。
pub fn fill<T: Clone>(a: &JArray<T>, value: T) {
    a.with_mut(|v| v.iter_mut().for_each(|x| *x = value.clone()));
}

/// `Arrays.fill(a, from, to, v)`。
pub fn fill_range<T: Clone>(a: &JArray<T>, from: i32, to: i32, value: T) {
    check_range(a.length(), from, to);
    a.with_mut(|v| v[from as usize..to as usize].iter_mut().for_each(|x| *x = value.clone()));
}

/// `Arrays.toString(a)`。
pub fn to_string<T: JStringify + Clone>(a: &JArray<T>) -> JString {
    let mut s = String::from("[");
    a.with_ref(|v| {
        for (i, x) in v.iter().enumerate() {
            if i > 0 {
                s.push_str(", ");
            }
            x.append_to(&mut s);
        }
    });
    s.push(']');
    JString::from(s)
}

/// `Arrays.toString(char[])`。
pub fn to_string_chars(a: &JArray<u16>) -> JString {
    let v: Vec<crate::JChar> = a.to_vec().into_iter().map(crate::JChar).collect();
    to_string(&JArray::from_vec(v))
}

/// `Arrays.copyOf(a, newLength)`。
pub fn copy_of<T: Clone + Default>(a: &JArray<T>, new_length: i32) -> JArray<T> {
    if new_length < 0 {
        throw("java.lang.NegativeArraySizeException", Some(&new_length.to_string()));
    }
    let mut v = a.to_vec();
    v.resize(new_length as usize, T::default());
    JArray::from_vec(v)
}

/// `Arrays.copyOfRange(a, from, to)`。
pub fn copy_of_range<T: Clone + Default>(a: &JArray<T>, from: i32, to: i32) -> JArray<T> {
    let len = a.length();
    if from < 0 || from > len {
        throw("java.lang.ArrayIndexOutOfBoundsException", Some(&format!("Index {from} out of bounds for length {len}")));
    }
    if from > to {
        throw("java.lang.IllegalArgumentException", Some(&format!("{from} > {to}")));
    }
    let v = a.to_vec();
    let mut out: Vec<T> = v[from as usize..(to.min(len)) as usize].to_vec();
    out.resize((to - from) as usize, T::default());
    JArray::from_vec(out)
}

/// `Arrays.equals(a, b)`。
pub fn equals<T: PartialEq + Clone>(a: &JArray<T>, b: &JArray<T>) -> bool {
    a.with_ref(|x| b.with_ref(|y| x == y))
}

fn check_range(len: i32, from: i32, to: i32) {
    if from > to {
        throw("java.lang.IllegalArgumentException", Some(&format!("fromIndex({from}) > toIndex({to})")));
    }
    if from < 0 {
        throw("java.lang.ArrayIndexOutOfBoundsException", Some(&format!("Array index out of range: {from}")));
    }
    if to > len {
        throw("java.lang.ArrayIndexOutOfBoundsException", Some(&format!("Array index out of range: {to}")));
    }
}

/// `Arrays.sort(T[], comparator)` / `Arrays.sort(Object[])`（安定ソート。comparator が null なら compareTo）。
/// 要素は String でも Object でもよい（比較のときに Object として扱う）。
pub fn sort_objects<T: Clone + Into<crate::JObject>>(a: &JArray<T>, comparator: &crate::JObject) {
    let mut v = a.to_vec();
    v.sort_by(|x, y| crate::util::core::compare_with(comparator, &x.clone().into(), &y.clone().into()).cmp(&0));
    a.with_mut(|dst| *dst = v);
}

/// `Arrays.sort(T[], from, to, comparator)`。
pub fn sort_objects_range<T: Clone + Into<crate::JObject>>(a: &JArray<T>, from: i32, to: i32, comparator: &crate::JObject) {
    check_range(a.length(), from, to);
    let mut v = a.to_vec();
    v[from as usize..to as usize].sort_by(|x, y| crate::util::core::compare_with(comparator, &x.clone().into(), &y.clone().into()).cmp(&0));
    a.with_mut(|dst| *dst = v);
}

/// `Arrays.hashCode(int[])` など。
pub fn hash_code<T: Clone + JavaHash>(a: &JArray<T>) -> i32 {
    if a.is_null() {
        return 0;
    }
    a.to_vec().iter().fold(1i32, |h, x| h.wrapping_mul(31).wrapping_add(x.java_hash()))
}

/// 要素の hashCode（`Arrays.hashCode` 用）。
pub trait JavaHash {
    fn java_hash(&self) -> i32;
}

impl JavaHash for i32 {
    fn java_hash(&self) -> i32 {
        *self
    }
}
impl JavaHash for i64 {
    fn java_hash(&self) -> i32 {
        (*self ^ ((*self as u64) >> 32) as i64) as i32
    }
}
impl JavaHash for i8 {
    fn java_hash(&self) -> i32 {
        *self as i32
    }
}
impl JavaHash for i16 {
    fn java_hash(&self) -> i32 {
        *self as i32
    }
}
impl JavaHash for f64 {
    fn java_hash(&self) -> i32 {
        let bits = if self.is_nan() { 0x7ff8000000000000u64 } else { self.to_bits() };
        (bits ^ (bits >> 32)) as i32
    }
}
impl JavaHash for f32 {
    fn java_hash(&self) -> i32 {
        if self.is_nan() { 0x7fc00000 } else { self.to_bits() as i32 }
    }
}
impl<T> JavaHash for JArray<T> {
    fn java_hash(&self) -> i32 {
        if self.is_null() { 0 } else { self.length().wrapping_mul(0x61c8_8647) }
    }
}
impl JavaHash for u16 {
    fn java_hash(&self) -> i32 {
        *self as i32
    }
}
impl JavaHash for bool {
    fn java_hash(&self) -> i32 {
        if *self { 1231 } else { 1237 }
    }
}
impl JavaHash for JString {
    fn java_hash(&self) -> i32 {
        if self.is_null() { 0 } else { self.hash_code() }
    }
}
impl JavaHash for crate::JObject {
    fn java_hash(&self) -> i32 {
        crate::object::hash_nullable(self)
    }
}

/// 配列の文字列化（`"" + array` / `println(array)`）: Java と同じく `型@ハッシュ` 形式。
impl<T> JStringify for JArray<T> {
    fn append_to(&self, out: &mut String) {
        if self.is_null() {
            out.push_str("null");
        } else {
            out.push_str("[@");
            out.push_str(&format!("{:x}", self.length() as u32 ^ 0x1b6d_3586));
        }
    }
}

/// `Arrays.deepToString(Object[])`（多次元配列の中身も文字列にする）。
pub fn deep_to_string<T: DeepToString>(a: &T) -> JString {
    let mut s = String::new();
    a.deep(&mut s);
    JString::from(s)
}

/// `Arrays.deepToString` の要素。
pub trait DeepToString {
    fn deep(&self, out: &mut String);
}

impl<T: DeepToString + Clone> DeepToString for JArray<T> {
    fn deep(&self, out: &mut String) {
        if self.is_null() {
            out.push_str("null");
            return;
        }
        out.push('[');
        for (i, x) in self.to_vec().iter().enumerate() {
            if i > 0 {
                out.push_str(", ");
            }
            x.deep(out);
        }
        out.push(']');
    }
}

macro_rules! deep_leaf {
    ($($t:ty),*) => {
        $(impl DeepToString for $t {
            fn deep(&self, out: &mut String) {
                self.append_to(out)
            }
        })*
    };
}
deep_leaf!(i8, i16, i32, i64, f32, f64, bool, JString, crate::JObject);

impl DeepToString for u16 {
    fn deep(&self, out: &mut String) {
        crate::JChar(*self).append_to(out)
    }
}

/// `Arrays.stream(a).sum()` の代わりに使う合計（int[]）。
pub fn sum_i32(a: &JArray<i32>) -> i32 {
    a.to_vec().iter().fold(0i32, |s, x| s.wrapping_add(*x))
}
