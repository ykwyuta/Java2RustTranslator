//! java.util.Arrays の static メソッド。

use crate::array::JArray;
use crate::lang::string::JString;
use crate::lang::stringify::{JStringify, JToString};
use crate::rt::{throw, JResult};
use std::cmp::Ordering;

/// `Arrays.sort(int[])` など（整数・char）。
pub fn sort<T: Ord + Clone>(a: &JArray<T>) -> JResult<()> {
    a.with_mut(|v| v.sort())
}

/// `Arrays.sort(a, from, to)`。
pub fn sort_range<T: Ord + Clone>(a: &JArray<T>, from: i32, to: i32) -> JResult<()> {
    check_range(a.length()?, from, to)?;
    a.with_mut(|v| v[from as usize..to as usize].sort())
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
pub fn sort_f64(a: &JArray<f64>) -> JResult<()> {
    a.with_mut(|v| v.sort_by(java_cmp_f64))
}

/// `Arrays.sort(float[])`。
pub fn sort_f32(a: &JArray<f32>) -> JResult<()> {
    a.with_mut(|v| v.sort_by(|x, y| java_cmp_f64(&(*x as f64), &(*y as f64))))
}

/// `Arrays.fill(a, v)`。
pub fn fill<T: Clone>(a: &JArray<T>, value: T) -> JResult<()> {
    a.with_mut(|v| v.iter_mut().for_each(|x| *x = value.clone()))
}

/// `Arrays.fill(a, from, to, v)`。
pub fn fill_range<T: Clone>(a: &JArray<T>, from: i32, to: i32, value: T) -> JResult<()> {
    check_range(a.length()?, from, to)?;
    a.with_mut(|v| v[from as usize..to as usize].iter_mut().for_each(|x| *x = value.clone()))
}

/// `Arrays.toString(a)`（null なら "null"）。
pub fn to_string<T: JToString + Clone>(a: &JArray<T>) -> JResult<JString> {
    if a.is_null() {
        return Ok(JString::from("null"));
    }
    let mut s = String::from("[");
    for (i, x) in a.to_vec()?.iter().enumerate() {
        if i > 0 {
            s.push_str(", ");
        }
        x.append_java(&mut s)?;
    }
    s.push(']');
    Ok(JString::from(s))
}

/// `Arrays.toString(char[])`。
pub fn to_string_chars(a: &JArray<u16>) -> JResult<JString> {
    if a.is_null() {
        return Ok(JString::from("null"));
    }
    let v: Vec<crate::JChar> = a.to_vec()?.into_iter().map(crate::JChar).collect();
    to_string(&JArray::from_vec(v))
}

/// `Arrays.copyOf(a, newLength)`。
pub fn copy_of<T: Clone + Default>(a: &JArray<T>, new_length: i32) -> JResult<JArray<T>> {
    if new_length < 0 {
        return throw("java.lang.NegativeArraySizeException", Some(&new_length.to_string()));
    }
    let mut v = a.to_vec()?;
    v.resize(new_length as usize, T::default());
    Ok(JArray::from_vec(v))
}

/// `Arrays.copyOfRange(a, from, to)`。
pub fn copy_of_range<T: Clone + Default>(a: &JArray<T>, from: i32, to: i32) -> JResult<JArray<T>> {
    let len = a.length()?;
    if from < 0 || from > len {
        return throw("java.lang.ArrayIndexOutOfBoundsException", Some(&format!("Index {from} out of bounds for length {len}")));
    }
    if from > to {
        return throw("java.lang.IllegalArgumentException", Some(&format!("{from} > {to}")));
    }
    let v = a.to_vec()?;
    let mut out: Vec<T> = v[from as usize..(to.min(len)) as usize].to_vec();
    out.resize((to - from) as usize, T::default());
    Ok(JArray::from_vec(out))
}

/// `Arrays.equals(a, b)`（プリミティブ型・String の配列。null どうしは等しい）。
pub fn equals<T: PartialEq + Clone>(a: &JArray<T>, b: &JArray<T>) -> bool {
    match (a.to_vec(), b.to_vec()) {
        (Ok(x), Ok(y)) => x == y,
        _ => a.is_null() && b.is_null(),
    }
}

fn check_range(len: i32, from: i32, to: i32) -> JResult<()> {
    if from > to {
        return throw("java.lang.IllegalArgumentException", Some(&format!("fromIndex({from}) > toIndex({to})")));
    }
    if from < 0 {
        return throw("java.lang.ArrayIndexOutOfBoundsException", Some(&format!("Array index out of range: {from}")));
    }
    if to > len {
        return throw("java.lang.ArrayIndexOutOfBoundsException", Some(&format!("Array index out of range: {to}")));
    }
    Ok(())
}

/// `Arrays.sort(T[], comparator)` / `Arrays.sort(Object[])` / `Arrays.sort(String[])`
/// （安定ソート。comparator が null なら compareTo）。要素は String でも Object でもよい（比較のときに Object として扱う）。
pub fn sort_objects<T: Clone + Into<crate::JObject>>(a: &JArray<T>, comparator: &crate::JObject) -> JResult<()> {
    let mut v = a.to_vec()?;
    crate::util::core::try_sort_by(&mut v, |x, y| {
        Ok(crate::util::core::compare_with(comparator, &x.clone().into(), &y.clone().into())?.cmp(&0))
    })?;
    a.with_mut(|dst| *dst = v)
}

/// `Arrays.sort(T[], from, to, comparator)`。
pub fn sort_objects_range<T: Clone + Into<crate::JObject>>(a: &JArray<T>, from: i32, to: i32, comparator: &crate::JObject) -> JResult<()> {
    check_range(a.length()?, from, to)?;
    let v = a.to_vec()?;
    let mut part = v[from as usize..to as usize].to_vec();
    crate::util::core::try_sort_by(&mut part, |x, y| {
        Ok(crate::util::core::compare_with(comparator, &x.clone().into(), &y.clone().into())?.cmp(&0))
    })?;
    a.with_mut(|dst| dst[from as usize..to as usize].clone_from_slice(&part))
}

/// `Arrays.hashCode(int[])` など。
pub fn hash_code<T: Clone + JavaHash>(a: &JArray<T>) -> JResult<i32> {
    if a.is_null() {
        return Ok(0);
    }
    let mut h = 1i32;
    for x in a.to_vec()? {
        h = h.wrapping_mul(31).wrapping_add(x.java_hash()?);
    }
    Ok(h)
}

/// 要素の hashCode（`Arrays.hashCode` 用）。
pub trait JavaHash {
    fn java_hash(&self) -> JResult<i32>;
}

impl JavaHash for i32 {
    fn java_hash(&self) -> JResult<i32> {
        Ok(*self)
    }
}
impl JavaHash for i64 {
    fn java_hash(&self) -> JResult<i32> {
        Ok((*self ^ ((*self as u64) >> 32) as i64) as i32)
    }
}
impl JavaHash for i8 {
    fn java_hash(&self) -> JResult<i32> {
        Ok(*self as i32)
    }
}
impl JavaHash for i16 {
    fn java_hash(&self) -> JResult<i32> {
        Ok(*self as i32)
    }
}
impl JavaHash for f64 {
    fn java_hash(&self) -> JResult<i32> {
        let bits = if self.is_nan() { 0x7ff8000000000000u64 } else { self.to_bits() };
        Ok((bits ^ (bits >> 32)) as i32)
    }
}
impl JavaHash for f32 {
    fn java_hash(&self) -> JResult<i32> {
        Ok(if self.is_nan() { 0x7fc00000 } else { self.to_bits() as i32 })
    }
}
impl<T> JavaHash for JArray<T> {
    fn java_hash(&self) -> JResult<i32> {
        Ok(if self.is_null() { 0 } else { self.length().unwrap_or(0).wrapping_mul(0x61c8_8647) })
    }
}
impl JavaHash for u16 {
    fn java_hash(&self) -> JResult<i32> {
        Ok(*self as i32)
    }
}
impl JavaHash for bool {
    fn java_hash(&self) -> JResult<i32> {
        Ok(if *self { 1231 } else { 1237 })
    }
}
impl JavaHash for JString {
    fn java_hash(&self) -> JResult<i32> {
        if self.is_null() { Ok(0) } else { self.hash_code() }
    }
}
impl JavaHash for crate::JObject {
    fn java_hash(&self) -> JResult<i32> {
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
            out.push_str(&format!("{:x}", self.length().unwrap_or(0) as u32 ^ 0x1b6d_3586));
        }
    }
}

/// `Arrays.deepToString(Object[])`（多次元配列の中身も文字列にする）。
pub fn deep_to_string<T: DeepToString>(a: &T) -> JResult<JString> {
    let mut s = String::new();
    a.deep(&mut s)?;
    Ok(JString::from(s))
}

/// `Arrays.deepToString` の要素。
pub trait DeepToString {
    fn deep(&self, out: &mut String) -> JResult<()>;
}

impl<T: DeepToString + Clone> DeepToString for JArray<T> {
    fn deep(&self, out: &mut String) -> JResult<()> {
        if self.is_null() {
            out.push_str("null");
            return Ok(());
        }
        out.push('[');
        for (i, x) in self.to_vec()?.iter().enumerate() {
            if i > 0 {
                out.push_str(", ");
            }
            x.deep(out)?;
        }
        out.push(']');
        Ok(())
    }
}

macro_rules! deep_leaf {
    ($($t:ty),*) => {
        $(impl DeepToString for $t {
            fn deep(&self, out: &mut String) -> JResult<()> {
                self.append_java(out)
            }
        })*
    };
}
deep_leaf!(i8, i16, i32, i64, f32, f64, bool, JString, crate::JObject);

impl DeepToString for u16 {
    fn deep(&self, out: &mut String) -> JResult<()> {
        crate::JChar(*self).append_to(out);
        Ok(())
    }
}

/// `Arrays.stream(a).sum()` の代わりに使う合計（int[]）。
pub fn sum_i32(a: &JArray<i32>) -> JResult<i32> {
    Ok(a.to_vec()?.iter().fold(0i32, |s, x| s.wrapping_add(*x)))
}
