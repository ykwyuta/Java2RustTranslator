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
