//! java.lang.Enum 相当。

use crate::lang::string::JString;
use crate::object::JObject;

/// enum 定数の名前と序数（enum のルート構造体が持つ）。
pub struct EnumBase {
    name: JString,
    ordinal: i32,
}

impl EnumBase {
    pub fn new(name: JString, ordinal: i32) -> EnumBase {
        EnumBase { name, ordinal }
    }
}

fn of(this: &JObject) -> &EnumBase {
    match this.obj().as_enum() {
        Some(e) => e,
        None => crate::object::bad_cast(this, "java.lang.Enum"),
    }
}

/// `ordinal()`。
pub fn ordinal(this: &JObject) -> i32 {
    of(this).ordinal
}

/// `name()`（既定の `toString()` も同じ）。
pub fn name(this: &JObject) -> JString {
    of(this).name.clone()
}

/// `compareTo(E)`: 序数の差。
pub fn compare(this: &JObject, other: &JObject) -> i32 {
    ordinal(this) - ordinal(other)
}

/// `valueOf(String)`: 名前で定数を探す。なければ IllegalArgumentException。
pub fn value_of(values: &crate::JArray<JObject>, class: &str, name: &JString) -> JObject {
    for v in values.to_vec() {
        if of(&v).name.equals(name) {
            return v;
        }
    }
    crate::rt::throw(
        "java.lang.IllegalArgumentException",
        Some(&format!("No enum constant {class}.{}", name.as_str())),
    )
}
