//! ボクシングされたプリミティブ値（Integer, Long, Double, ... ）。
//!
//! `Integer.valueOf` と同様に -128..=127 の Integer / Short / Byte / Long と 0..=127 の Character、
//! Boolean はキャッシュするので、`==`（参照の同一性）の結果も Java と一致する。

use crate::lang::string::JString;
use crate::lang::stringify::{double_to_string, float_to_string, JChar, JStringify};
use crate::object::{alloc, class_cast, JObject, Object, ObjectBase};
use crate::rt::{npe, JResult};
use std::cell::RefCell;

macro_rules! boxed {
    ($name:ident, $t:ty, $class:literal, [$($iface:literal),*], $hash:expr, $show:expr, $cmp:expr) => {
        pub struct $name {
            base: ObjectBase,
            pub value: $t,
        }

        impl Object for $name {
            fn base(&self) -> &ObjectBase {
                &self.base
            }
            fn class_name(&self) -> &'static str {
                $class
            }
            fn instance_of(&self, class: &str) -> bool {
                matches!(class, $class | "java.lang.Object" | "java.io.Serializable" | "java.lang.Comparable" $(| $iface)*)
            }
            fn to_jstring(&self) -> JResult<JString> {
                let f: fn($t) -> String = $show;
                Ok(JString::from(f(self.value)))
            }
            fn equals(&self, other: &JObject) -> JResult<bool> {
                // Double/Float の equals はビット表現で比べる（NaN == NaN、0.0 != -0.0）。
                Ok(other.downcast_ref::<$name>().is_some_and(|o| same_bits(o.value, self.value)))
            }
            fn hash_code(&self) -> JResult<i32> {
                let h: fn($t) -> i32 = $hash;
                Ok(h(self.value))
            }
            fn compare_to(&self, other: &JObject) -> JResult<i32> {
                match other.downcast_ref::<$name>() {
                    Some(o) => {
                        let c: fn($t, $t) -> i32 = $cmp;
                        Ok(c(self.value, o.value))
                    }
                    None if other.is_null() => npe(),
                    None => class_cast(other, $class),
                }
            }
        }
    };
}

trait SameBits {
    fn bits(self) -> u64;
}
impl SameBits for i32 { fn bits(self) -> u64 { self as u64 } }
impl SameBits for i64 { fn bits(self) -> u64 { self as u64 } }
impl SameBits for i16 { fn bits(self) -> u64 { self as u64 } }
impl SameBits for i8 { fn bits(self) -> u64 { self as u64 } }
impl SameBits for u16 { fn bits(self) -> u64 { self as u64 } }
impl SameBits for bool { fn bits(self) -> u64 { self as u64 } }
impl SameBits for f64 { fn bits(self) -> u64 { if self.is_nan() { f64::NAN.to_bits() } else { self.to_bits() } } }
impl SameBits for f32 { fn bits(self) -> u64 { if self.is_nan() { f32::NAN.to_bits() as u64 } else { self.to_bits() as u64 } } }
fn same_bits<T: SameBits>(a: T, b: T) -> bool {
    a.bits() == b.bits()
}

fn cmp_ord<T: PartialOrd>(a: T, b: T) -> i32 {
    if a < b { -1 } else if a > b { 1 } else { 0 }
}

boxed!(JInteger, i32, "java.lang.Integer", ["java.lang.Number"], |v| v, |v| v.to_string(), cmp_ord);
boxed!(JLong, i64, "java.lang.Long", ["java.lang.Number"], |v| (v ^ ((v as u64) >> 32) as i64) as i32, |v| v.to_string(), cmp_ord);
boxed!(JShort, i16, "java.lang.Short", ["java.lang.Number"], |v| v as i32, |v| v.to_string(), |a, b| a as i32 - b as i32);
boxed!(JByte, i8, "java.lang.Byte", ["java.lang.Number"], |v| v as i32, |v| v.to_string(), |a, b| a as i32 - b as i32);
boxed!(JCharacter, u16, "java.lang.Character", [], |v| v as i32, |v| String::from_utf16_lossy(&[v]), |a, b| a as i32 - b as i32);
boxed!(JBoolean, bool, "java.lang.Boolean", [], |v| if v { 1231 } else { 1237 }, |v| v.to_string(), |a, b| (a as i32) - (b as i32));
boxed!(JDouble, f64, "java.lang.Double", ["java.lang.Number"], |v| {
    let bits = if v.is_nan() { 0x7ff8000000000000u64 } else { v.to_bits() };
    (bits ^ (bits >> 32)) as i32
}, double_to_string, |a, b| crate::util::arrays::java_cmp_f64(&a, &b) as i32);
boxed!(JFloat, f32, "java.lang.Float", ["java.lang.Number"], |v| {
    if v.is_nan() { 0x7fc00000 } else { v.to_bits() as i32 }
}, float_to_string, |a, b| crate::util::arrays::java_cmp_f64(&(a as f64), &(b as f64)) as i32);

thread_local! {
    static INT_CACHE: RefCell<Vec<JObject>> = const { RefCell::new(Vec::new()) };
    static LONG_CACHE: RefCell<Vec<JObject>> = const { RefCell::new(Vec::new()) };
    static SMALL_CACHE: RefCell<Vec<JObject>> = const { RefCell::new(Vec::new()) };
}

fn cached(cache: &'static std::thread::LocalKey<RefCell<Vec<JObject>>>, idx: usize, make: impl Fn(usize) -> JObject, len: usize) -> JObject {
    cache.with(|c| {
        let mut c = c.borrow_mut();
        if c.is_empty() {
            *c = (0..len).map(&make).collect();
        }
        c[idx].clone()
    })
}

/// `Integer.valueOf(int)`（自動ボクシング）。
pub fn box_i32(v: i32) -> JObject {
    if (-128..=127).contains(&v) {
        return cached(&INT_CACHE, (v + 128) as usize, |i| alloc(|base| JInteger { base, value: i as i32 - 128 }), 256);
    }
    alloc(|base| JInteger { base, value: v })
}

/// `Long.valueOf(long)`。
pub fn box_i64(v: i64) -> JObject {
    if (-128..=127).contains(&v) {
        return cached(&LONG_CACHE, (v + 128) as usize, |i| alloc(|base| JLong { base, value: i as i64 - 128 }), 256);
    }
    alloc(|base| JLong { base, value: v })
}

/// `Short.valueOf(short)`。
pub fn box_i16(v: i16) -> JObject {
    alloc(|base| JShort { base, value: v })
}

/// `Byte.valueOf(byte)`。
pub fn box_i8(v: i8) -> JObject {
    alloc(|base| JByte { base, value: v })
}

/// `Character.valueOf(char)`。
pub fn box_u16(v: u16) -> JObject {
    if v < 128 {
        return cached(&SMALL_CACHE, v as usize, |i| alloc(|base| JCharacter { base, value: i as u16 }), 128);
    }
    alloc(|base| JCharacter { base, value: v })
}

thread_local! {
    static BOOLS: [JObject; 2] = [alloc(|base| JBoolean { base, value: false }), alloc(|base| JBoolean { base, value: true })];
}

/// `Boolean.valueOf(boolean)`。
pub fn box_bool(v: bool) -> JObject {
    BOOLS.with(|b| b[v as usize].clone())
}

/// `Double.valueOf(double)`。
pub fn box_f64(v: f64) -> JObject {
    alloc(|base| JDouble { base, value: v })
}

/// `Float.valueOf(float)`。
pub fn box_f32(v: f32) -> JObject {
    alloc(|base| JFloat { base, value: v })
}

macro_rules! unbox {
    ($fn:ident, $t:ty, $struct:ident, $class:literal) => {
        /// 自動アンボクシング（null なら NullPointerException、型が違えば ClassCastException）。
        pub fn $fn(&self) -> JResult<$t> {
            match self.downcast_ref::<$struct>() {
                Some(b) => Ok(b.value),
                None => {
                    self.obj()?;
                    class_cast(self, $class)
                }
            }
        }
    };
}

impl JObject {
    unbox!(unbox_i32, i32, JInteger, "java.lang.Integer");
    unbox!(unbox_i64, i64, JLong, "java.lang.Long");
    unbox!(unbox_i16, i16, JShort, "java.lang.Short");
    unbox!(unbox_i8, i8, JByte, "java.lang.Byte");
    unbox!(unbox_u16, u16, JCharacter, "java.lang.Character");
    unbox!(unbox_bool, bool, JBoolean, "java.lang.Boolean");
    unbox!(unbox_f64, f64, JDouble, "java.lang.Double");
    unbox!(unbox_f32, f32, JFloat, "java.lang.Float");
}

/// `Number.doubleValue()` など: 数値のボックスを f64 として読む。
pub fn number_f64(o: &JObject) -> JResult<f64> {
    if let Some(b) = o.downcast_ref::<JInteger>() { return Ok(b.value as f64); }
    if let Some(b) = o.downcast_ref::<JLong>() { return Ok(b.value as f64); }
    if let Some(b) = o.downcast_ref::<JDouble>() { return Ok(b.value); }
    if let Some(b) = o.downcast_ref::<JFloat>() { return Ok(b.value as f64); }
    if let Some(b) = o.downcast_ref::<JShort>() { return Ok(b.value as f64); }
    if let Some(b) = o.downcast_ref::<JByte>() { return Ok(b.value as f64); }
    o.obj()?;
    class_cast(o, "java.lang.Number")
}

/// `Number.longValue()`。
pub fn number_i64(o: &JObject) -> JResult<i64> {
    if let Some(b) = o.downcast_ref::<JInteger>() { return Ok(b.value as i64); }
    if let Some(b) = o.downcast_ref::<JLong>() { return Ok(b.value); }
    if let Some(b) = o.downcast_ref::<JShort>() { return Ok(b.value as i64); }
    if let Some(b) = o.downcast_ref::<JByte>() { return Ok(b.value as i64); }
    Ok(number_f64(o)? as i64)
}

/// `Number.intValue()`。
pub fn number_i32(o: &JObject) -> JResult<i32> {
    if let Some(b) = o.downcast_ref::<JDouble>() { return Ok(b.value as i32); }
    if let Some(b) = o.downcast_ref::<JFloat>() { return Ok(b.value as i32); }
    Ok(number_i64(o)? as i32)
}

/// ボックスの toString（`Integer.toString()` 等の文字列化で使う）。
pub fn to_string_value<T: JStringify>(v: T) -> JString {
    JString::value_of(&v)
}

/// `Character` の文字列化用。
pub fn char_to_string(c: u16) -> JString {
    JString::value_of(&JChar(c))
}
