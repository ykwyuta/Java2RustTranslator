//! java.lang.Enum 相当。

use crate::lang::string::JString;
use crate::object::JObject;
use crate::rt::JResult;

/// enum 定数の名前と序数（enum のルート構造体が持つ）。
pub struct EnumBase {
    name: JString,
    ordinal: i32,
    /// enum のクラス名（本体付きの定数でも enum 自身の名前。`getDeclaringClass()` 用）。
    class: &'static str,
    /// その enum のすべての定数（EnumSet.range / complementOf 用）。
    values: fn() -> crate::JArray<JObject>,
}

impl EnumBase {
    pub fn new(name: JString, ordinal: i32, class: &'static str, values: fn() -> crate::JArray<JObject>) -> EnumBase {
        EnumBase { name, ordinal, class, values }
    }
}

/// 定数 this の enum のすべての定数。
pub fn values_of(this: &JObject) -> JResult<crate::JArray<JObject>> {
    Ok((of(this)?.values)())
}

/// `getDeclaringClass()`。
pub fn declaring_class(this: &JObject) -> JResult<JObject> {
    let e = of(this)?;
    Ok(crate::util::misc::enum_class_for(e.class, e.values))
}

fn of(this: &JObject) -> JResult<&EnumBase> {
    match this.obj()?.as_enum() {
        Some(e) => Ok(e),
        None => crate::object::class_cast(this, "java.lang.Enum"),
    }
}

/// `ordinal()`（null なら NullPointerException。enum の switch のセレクタにも使う）。
pub fn ordinal(this: &JObject) -> JResult<i32> {
    Ok(of(this)?.ordinal)
}

/// `name()`（既定の `toString()` も同じ）。
pub fn name(this: &JObject) -> JResult<JString> {
    Ok(of(this)?.name.clone())
}

/// `compareTo(E)`: 序数の差。
pub fn compare(this: &JObject, other: &JObject) -> JResult<i32> {
    Ok(ordinal(this)? - ordinal(other)?)
}

/// `valueOf(String)`: 名前で定数を探す。なければ IllegalArgumentException。
pub fn value_of(values: &crate::JArray<JObject>, class: &str, name: &JString) -> JResult<JObject> {
    let wanted = name.as_str()?;
    for v in values.to_vec()? {
        if of(&v)?.name.opt_str() == Some(wanted) {
            return Ok(v);
        }
    }
    crate::rt::throw("java.lang.IllegalArgumentException", Some(&format!("No enum constant {class}.{wanted}")))
}
