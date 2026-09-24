//! Java の文字列変換（`"" + x`、`String.valueOf(x)`、`println(x)`）。

use crate::lang::string::JString;

/// Java の文字列変換規則で自分自身を文字列に追記できる型。
pub trait JStringify {
    fn append_to(&self, out: &mut String);

    /// UTF-16 のまま追記する（`StringBuilder` 用）。サロゲートを保つため `JChar` は上書きする。
    fn append_utf16(&self, out: &mut Vec<u16>) {
        let mut s = String::new();
        self.append_to(&mut s);
        out.extend(s.encode_utf16());
    }
}

/// Java の `char`（UTF-16 コード単位）。Rust 側では `u16` で保持し、文字列化の時だけこの型で包む。
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct JChar(pub u16);

impl JStringify for JChar {
    fn append_to(&self, out: &mut String) {
        out.push(char::from_u32(self.0 as u32).unwrap_or(char::REPLACEMENT_CHARACTER));
    }

    fn append_utf16(&self, out: &mut Vec<u16>) {
        out.push(self.0);
    }
}

impl<T: JStringify + ?Sized> JStringify for &T {
    fn append_to(&self, out: &mut String) {
        (**self).append_to(out)
    }

    fn append_utf16(&self, out: &mut Vec<u16>) {
        (**self).append_utf16(out)
    }
}

impl JStringify for str {
    fn append_to(&self, out: &mut String) {
        out.push_str(self)
    }
}

impl JStringify for JString {
    fn append_to(&self, out: &mut String) {
        out.push_str(self.as_str())
    }
}

impl JStringify for bool {
    fn append_to(&self, out: &mut String) {
        out.push_str(if *self { "true" } else { "false" })
    }
}

macro_rules! int_stringify {
    ($($t:ty),*) => {
        $(impl JStringify for $t {
            fn append_to(&self, out: &mut String) {
                use std::fmt::Write;
                let _ = write!(out, "{}", self);
            }
        })*
    };
}
int_stringify!(i8, i16, i32, i64);

impl JStringify for f64 {
    fn append_to(&self, out: &mut String) {
        out.push_str(&double_to_string(*self))
    }
}

impl JStringify for f32 {
    fn append_to(&self, out: &mut String) {
        out.push_str(&float_to_string(*self))
    }
}

/// `Double.toString(d)`: 10^-3 <= |d| < 10^7 は小数表記、それ以外は `1.0E10` 形式。
pub fn double_to_string(d: f64) -> String {
    if d.is_nan() {
        return "NaN".into();
    }
    if d.is_infinite() {
        return if d > 0.0 { "Infinity".into() } else { "-Infinity".into() };
    }
    if d == 0.0 {
        return if d.is_sign_negative() { "-0.0".into() } else { "0.0".into() };
    }
    let a = d.abs();
    if (1e-3..1e7).contains(&a) {
        // Rust の Debug 表記は最短表現で、整数値でも ".0" を付ける。
        let s = format!("{d:?}");
        if s.contains('e') {
            format!("{d}")
        } else {
            s
        }
    } else {
        to_java_scientific(&format!("{d:e}"))
    }
}

/// `Float.toString(f)`。
pub fn float_to_string(f: f32) -> String {
    if f.is_nan() {
        return "NaN".into();
    }
    if f.is_infinite() {
        return if f > 0.0 { "Infinity".into() } else { "-Infinity".into() };
    }
    if f == 0.0 {
        return if f.is_sign_negative() { "-0.0".into() } else { "0.0".into() };
    }
    let a = f.abs();
    if (1e-3..1e7).contains(&a) {
        let s = format!("{f:?}");
        if s.contains('e') {
            format!("{f}")
        } else {
            s
        }
    } else {
        to_java_scientific(&format!("{f:e}"))
    }
}

/// Rust の `1.5e-5` / `1e10` を Java の `1.5E-5` / `1.0E10` にする。
fn to_java_scientific(s: &str) -> String {
    let (mantissa, exp) = s.split_once('e').unwrap_or((s, "0"));
    let mantissa = if mantissa.contains('.') { mantissa.to_string() } else { format!("{mantissa}.0") };
    format!("{mantissa}E{exp}")
}
