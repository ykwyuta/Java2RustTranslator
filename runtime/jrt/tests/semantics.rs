//! Java の意味論どおりに動くことの確認（期待値は JDK 21 の実際の出力）。

use jrt::lang::stringify::{double_to_string, float_to_string};
use jrt::lang::{math, number, StringBuilder};
use jrt::prelude::*;

#[test]
fn integer_arithmetic_wraps_like_java() {
    assert_eq!(jrt::num::div_i32(i32::MIN, -1), i32::MIN);
    assert_eq!(jrt::num::rem_i32(i32::MIN, -1), 0);
    assert_eq!(jrt::num::div_i32(-7, 2), -3);
    assert_eq!(jrt::num::rem_i32(-7, 2), -1);
    assert_eq!(i32::MAX.wrapping_add(1), i32::MIN);
    // Java: 1 << 33 == 2（シフト量は下位 5 ビット）
    assert_eq!(1i32.wrapping_shl(33), 2);
    // Java: -1 >>> 28 == 15
    assert_eq!((-1i32 as u32).wrapping_shr(28) as i32, 15);
}

#[test]
#[should_panic]
fn division_by_zero_throws() {
    jrt::num::div_i32(1, 0);
}

#[test]
fn casts_match_java() {
    // (int) 1e20 == Integer.MAX_VALUE, (int) NaN == 0, (byte) 300.0 == 44
    assert_eq!(1e20f64 as i32, i32::MAX);
    assert_eq!(f64::NAN as i32, 0);
    assert_eq!((300.0f64 as i32) as i8, 44);
}

#[test]
fn double_to_string_matches_java() {
    assert_eq!(double_to_string(1.0), "1.0");
    assert_eq!(double_to_string(0.1 + 0.2), "0.30000000000000004");
    assert_eq!(double_to_string(1e7), "1.0E7");
    assert_eq!(double_to_string(1.5e-5), "1.5E-5");
    assert_eq!(double_to_string(123456.789), "123456.789");
    assert_eq!(double_to_string(-0.0), "-0.0");
    assert_eq!(double_to_string(f64::NAN), "NaN");
    assert_eq!(double_to_string(f64::NEG_INFINITY), "-Infinity");
    assert_eq!(double_to_string(0.001), "0.001");
    assert_eq!(float_to_string(0.1f32), "0.1");
    assert_eq!(float_to_string(1e10f32), "1.0E10");
}

#[test]
fn concat_uses_java_string_conversion() {
    let s = jstr!("x=");
    let c = JChar(b'!' as u16);
    let r = jconcat!(s, 1, " ", 2.5, " ", true, c, 10i64);
    assert_eq!(r.as_str(), "x=1 2.5 true!10");
}

#[test]
fn string_methods_use_utf16_indices() {
    let s = JString::from("aあb");
    assert_eq!(s.length(), 3);
    assert_eq!(s.char_at(1), 'あ' as u16);
    assert_eq!(s.substring_range(1, 3).as_str(), "あb");
    assert_eq!(jstr!("hello").index_of(&jstr!("ll")), 2);
    assert_eq!(jstr!("apple").compare_to(&jstr!("banana")), -1);
    assert_eq!(jstr!("hello").hash_code(), 99162322);
    assert_eq!(jstr!("  hi ").trim().as_str(), "hi");
}

#[test]
fn string_builder_shares_state() {
    let sb = StringBuilder::new();
    let alias = sb.clone();
    sb.append(&jstr!("ab")).append(&1).append(&JChar(b'c' as u16));
    assert_eq!(alias.to_jstring().as_str(), "ab1c");
    assert_eq!(sb.reverse().to_jstring().as_str(), "c1ba");
}

#[test]
fn arrays_are_shared_and_bounds_checked() {
    let a: JArray<i32> = JArray::new(3);
    let b = a.clone();
    b.set(1, 42);
    assert_eq!(a.get(1), 42);
    assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| a.get(3))).is_err());
    let s = JArray::from_vec(vec![3, 1, 2]);
    jrt::util::arrays::sort(&s);
    assert_eq!(jrt::util::arrays::to_string(&s).as_str(), "[1, 2, 3]");
}

#[test]
fn math_and_numbers() {
    assert_eq!(math::round_f64(-2.5), -2);
    assert_eq!(math::round_f64(2.5), 3);
    assert_eq!(math::round_f64(0.49999999999999994), 0);
    assert_eq!(math::floor_mod_i32(-7, 3), 2);
    assert_eq!(math::floor_div_i32(-7, 3), -3);
    assert_eq!(number::parse_int(&jstr!("-123")), -123);
    assert_eq!(number::to_hex_string_i32(-1).as_str(), "ffffffff");
    assert!(math::max_f64(f64::NAN, 1.0).is_nan());
}
