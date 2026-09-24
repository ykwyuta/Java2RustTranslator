//! Java の整数演算のうち、Rust の演算子・wrapping_* だけでは表せないもの。

use crate::rt::throw;

/// Java の `int / int`。ゼロ除算は ArithmeticException、`MIN / -1` は `MIN`。
#[inline]
pub fn div_i32(a: i32, b: i32) -> i32 {
    if b == 0 {
        throw("java.lang.ArithmeticException", Some("/ by zero"));
    }
    a.wrapping_div(b)
}

/// Java の `int % int`。
#[inline]
pub fn rem_i32(a: i32, b: i32) -> i32 {
    if b == 0 {
        throw("java.lang.ArithmeticException", Some("/ by zero"));
    }
    a.wrapping_rem(b)
}

#[inline]
pub fn div_i64(a: i64, b: i64) -> i64 {
    if b == 0 {
        throw("java.lang.ArithmeticException", Some("/ by zero"));
    }
    a.wrapping_div(b)
}

#[inline]
pub fn rem_i64(a: i64, b: i64) -> i64 {
    if b == 0 {
        throw("java.lang.ArithmeticException", Some("/ by zero"));
    }
    a.wrapping_rem(b)
}
