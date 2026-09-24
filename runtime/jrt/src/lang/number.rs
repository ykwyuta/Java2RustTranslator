//! java.lang.Integer / Long / Double / Boolean の static メソッド。

use crate::lang::string::JString;
use crate::rt::{throw, JResult};

fn number_format<T>(s: &JString) -> JResult<T> {
    match s.opt_str() {
        Some(v) => throw("java.lang.NumberFormatException", Some(&format!("For input string: \"{v}\""))),
        None => throw("java.lang.NumberFormatException", Some("Cannot parse null string: null")),
    }
}

/// `Integer.parseInt(String)`。
pub fn parse_int(s: &JString) -> JResult<i32> {
    match s.opt_str().map(str::parse::<i32>) {
        Some(Ok(v)) => Ok(v),
        _ => number_format(s),
    }
}

/// `Integer.parseInt(String, int radix)`。
pub fn parse_int_radix(s: &JString, radix: i32) -> JResult<i32> {
    match s.opt_str().map(|v| i32::from_str_radix(v, radix as u32)) {
        Some(Ok(v)) => Ok(v),
        _ => number_format(s),
    }
}

/// `Long.parseLong(String)`。
pub fn parse_long(s: &JString) -> JResult<i64> {
    match s.opt_str().map(str::parse::<i64>) {
        Some(Ok(v)) => Ok(v),
        _ => number_format(s),
    }
}

/// `Double.parseDouble(String)`（前後の空白を許す）。
pub fn parse_double(s: &JString) -> JResult<f64> {
    let t = s.as_str()?.trim_matches(|c: char| c <= ' ');
    let t = t.strip_suffix(['d', 'D', 'f', 'F']).unwrap_or(t);
    match t {
        "NaN" => Ok(f64::NAN),
        "Infinity" | "+Infinity" => Ok(f64::INFINITY),
        "-Infinity" => Ok(f64::NEG_INFINITY),
        _ => t.parse::<f64>().or_else(|_| number_format(s)),
    }
}

/// `Boolean.parseBoolean(String)`（null は false）。
pub fn parse_boolean(s: &JString) -> bool {
    s.opt_str().is_some_and(|v| v.eq_ignore_ascii_case("true"))
}

/// `Integer.toBinaryString(int)`。
pub fn to_binary_string_i32(v: i32) -> JString {
    JString::from(format!("{:b}", v as u32))
}

/// `Integer.toHexString(int)`。
pub fn to_hex_string_i32(v: i32) -> JString {
    JString::from(format!("{:x}", v as u32))
}

/// `Integer.toOctalString(int)`。
pub fn to_octal_string_i32(v: i32) -> JString {
    JString::from(format!("{:o}", v as u32))
}

/// `Long.toBinaryString(long)`。
pub fn to_binary_string_i64(v: i64) -> JString {
    JString::from(format!("{:b}", v as u64))
}

/// `Long.toHexString(long)`。
pub fn to_hex_string_i64(v: i64) -> JString {
    JString::from(format!("{:x}", v as u64))
}

/// `Double.compare(a, b)`: -0.0 < 0.0、NaN は最大。
pub fn compare_f64(a: f64, b: f64) -> i32 {
    crate::util::arrays::java_cmp_f64(&a, &b) as i32
}
