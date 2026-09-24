//! java.util.Optional / OptionalInt / OptionalLong / OptionalDouble。

use crate::lang::string::JString;
use crate::object::{alloc, equals_nullable, hash_nullable, JObject, Object, ObjectBase};
use crate::rt::{npe, throw, JResult};

/// Optional の種類（toString と instanceof で区別する）。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum OptKind {
    Obj,
    Int,
    Long,
    Double,
}

pub struct JOptional {
    base: ObjectBase,
    kind: OptKind,
    value: Option<JObject>,
}

impl Object for JOptional {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        match self.kind {
            OptKind::Obj => "java.util.Optional",
            OptKind::Int => "java.util.OptionalInt",
            OptKind::Long => "java.util.OptionalLong",
            OptKind::Double => "java.util.OptionalDouble",
        }
    }
    fn instance_of(&self, class: &str) -> bool {
        class == "java.lang.Object" || class == self.class_name()
    }
    fn to_jstring(&self) -> JResult<JString> {
        let simple = &self.class_name()["java.util.".len()..];
        Ok(JString::from(match &self.value {
            None if self.kind == OptKind::Obj => "Optional.empty".to_string(),
            None => format!("{simple}.empty"),
            Some(v) => format!("{simple}[{}]", crate::lang::stringify::to_java_string(v)?),
        }))
    }
    fn equals(&self, other: &JObject) -> JResult<bool> {
        match other.downcast_ref::<JOptional>() {
            Some(o) if o.kind == self.kind => match (&self.value, &o.value) {
                (None, None) => Ok(true),
                (Some(a), Some(b)) => equals_nullable(a, b),
                _ => Ok(false),
            },
            _ => Ok(false),
        }
    }
    fn hash_code(&self) -> JResult<i32> {
        match &self.value {
            None => Ok(0),
            Some(v) => hash_nullable(v),
        }
    }
}

fn make(kind: OptKind, value: Option<JObject>) -> JObject {
    alloc(|base| JOptional { base, kind, value })
}

/// `Optional.empty()` など。
pub fn empty(kind: OptKind) -> JObject {
    make(kind, None)
}

/// `Optional.of(v)`（null なら NullPointerException）/ `OptionalInt.of(i)` など（値はボクシング済み）。
pub fn of(kind: OptKind, v: impl Into<JObject>) -> JResult<JObject> {
    let v = v.into();
    if v.is_null() {
        return npe();
    }
    Ok(make(kind, Some(v)))
}

/// `Optional.ofNullable(v)`。
pub fn of_nullable(v: impl Into<JObject>) -> JObject {
    let v = v.into();
    if v.is_null() {
        empty(OptKind::Obj)
    } else {
        make(OptKind::Obj, Some(v))
    }
}

fn opt(o: &JObject) -> JResult<&JOptional> {
    match o.downcast_ref::<JOptional>() {
        Some(x) => Ok(x),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.Optional")
        }
    }
}

fn no_value<T>() -> JResult<T> {
    throw("java.util.NoSuchElementException", Some("No value present"))
}

/// `isPresent()`。
pub fn is_present(o: &JObject) -> JResult<bool> {
    Ok(opt(o)?.value.is_some())
}

/// `isEmpty()`。
pub fn is_empty(o: &JObject) -> JResult<bool> {
    Ok(opt(o)?.value.is_none())
}

/// `get()` / `getAsInt()` / `orElseThrow()`（値はボクシングされたまま返す）。
pub fn get(o: &JObject) -> JResult<JObject> {
    match &opt(o)?.value {
        Some(v) => Ok(v.clone()),
        None => no_value(),
    }
}

/// `orElse(other)`。
pub fn or_else(o: &JObject, other: impl Into<JObject>) -> JResult<JObject> {
    let other = other.into();
    Ok(opt(o)?.value.clone().unwrap_or(other))
}

/// `orElseGet(supplier)`。
pub fn or_else_get(o: &JObject, supplier: &JObject) -> JResult<JObject> {
    match &opt(o)?.value {
        Some(v) => Ok(v.clone()),
        None => supplier.invoke("get", &[]),
    }
}

/// `orElseThrow(exceptionSupplier)`。
pub fn or_else_throw(o: &JObject, supplier: &JObject) -> JResult<JObject> {
    match &opt(o)?.value {
        Some(v) => Ok(v.clone()),
        None => crate::rt::throw_obj(supplier.invoke("get", &[])?),
    }
}

/// `ifPresent(action)`。
pub fn if_present(o: &JObject, action: &JObject) -> JResult<()> {
    if let Some(v) = &opt(o)?.value {
        action.invoke("accept", &[v.clone()])?;
    }
    Ok(())
}

/// `ifPresentOrElse(action, emptyAction)`。
pub fn if_present_or_else(o: &JObject, action: &JObject, empty_action: &JObject) -> JResult<()> {
    match &opt(o)?.value {
        Some(v) => action.invoke("accept", &[v.clone()])?,
        None => empty_action.invoke("run", &[])?,
    };
    Ok(())
}

/// `map(f)`（f の結果が null なら empty）。
pub fn map(o: &JObject, f: &JObject) -> JResult<JObject> {
    match &opt(o)?.value {
        Some(v) => Ok(of_nullable(f.invoke("apply", &[v.clone()])?)),
        None => Ok(empty(OptKind::Obj)),
    }
}

/// `flatMap(f)`。
pub fn flat_map(o: &JObject, f: &JObject) -> JResult<JObject> {
    match &opt(o)?.value {
        Some(v) => {
            let r = f.invoke("apply", &[v.clone()])?;
            if r.is_null() {
                return npe();
            }
            Ok(r)
        }
        None => Ok(empty(OptKind::Obj)),
    }
}

/// `filter(predicate)`。
pub fn filter(o: &JObject, p: &JObject) -> JResult<JObject> {
    let x = opt(o)?;
    match &x.value {
        Some(v) if p.invoke("test", &[v.clone()])?.unbox_bool()? => Ok(o.clone()),
        _ => Ok(empty(x.kind)),
    }
}

/// `or(supplier)`。
pub fn or(o: &JObject, supplier: &JObject) -> JResult<JObject> {
    match &opt(o)?.value {
        Some(_) => Ok(o.clone()),
        None => supplier.invoke("get", &[]),
    }
}

/// `stream()`。
pub fn stream(o: &JObject) -> JResult<JObject> {
    let items: Vec<JObject> = opt(o)?.value.iter().cloned().collect();
    Ok(crate::util::stream::from_vec(crate::util::stream::Kind::Obj, items))
}
