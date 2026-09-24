//! java.util.function と Comparator の既定メソッド・static メソッド。

use crate::lambda::lambda;
use crate::lang::boxed::{box_bool, box_i32};
use crate::object::JObject;
use crate::rt::JResult;
use crate::util::core::compare_with;

const COMPARATOR: &[&str] = &["java.util.Comparator"];

/// `Comparator.naturalOrder()`。
pub fn natural_order() -> JObject {
    lambda(COMPARATOR, |a| Ok(box_i32(a[0].compare_to(&a[1])?)))
}

/// `Comparator.reverseOrder()` / `Collections.reverseOrder()`。
pub fn reverse_order() -> JObject {
    lambda(COMPARATOR, |a| Ok(box_i32(a[1].compare_to(&a[0])?)))
}

/// `cmp.reversed()`。
pub fn reversed(cmp: &JObject) -> JResult<JObject> {
    cmp.obj()?;
    let cmp = cmp.clone();
    Ok(lambda(COMPARATOR, move |a| Ok(box_i32(compare_with(&cmp, &a[1], &a[0])?))))
}

/// `cmp.thenComparing(other)`。
pub fn then_comparing(cmp: &JObject, other: &JObject) -> JResult<JObject> {
    cmp.obj()?;
    other.obj()?;
    let (cmp, other) = (cmp.clone(), other.clone());
    Ok(lambda(COMPARATOR, move |a| {
        let c = compare_with(&cmp, &a[0], &a[1])?;
        Ok(box_i32(if c != 0 { c } else { compare_with(&other, &a[0], &a[1])? }))
    }))
}

/// `cmp.thenComparing(keyExtractor)`。
pub fn then_comparing_key(cmp: &JObject, key: &JObject) -> JResult<JObject> {
    then_comparing(cmp, &comparing(key)?)
}

/// `Comparator.comparing(keyExtractor)`。
pub fn comparing(key: &JObject) -> JResult<JObject> {
    key.obj()?;
    let key = key.clone();
    Ok(lambda(COMPARATOR, move |a| {
        let (x, y) = (key.invoke("apply", &[a[0].clone()])?, key.invoke("apply", &[a[1].clone()])?);
        Ok(box_i32(x.compare_to(&y)?))
    }))
}

/// `Comparator.comparing(keyExtractor, keyComparator)`。
pub fn comparing_with(key: &JObject, key_cmp: &JObject) -> JResult<JObject> {
    key.obj()?;
    key_cmp.obj()?;
    let (key, key_cmp) = (key.clone(), key_cmp.clone());
    Ok(lambda(COMPARATOR, move |a| {
        let (x, y) = (key.invoke("apply", &[a[0].clone()])?, key.invoke("apply", &[a[1].clone()])?);
        Ok(box_i32(compare_with(&key_cmp, &x, &y)?))
    }))
}

/// `Comparator.comparingInt` / `comparingLong` / `comparingDouble`（キーはボクシングされた数値）。
pub fn comparing_number(key: &JObject, method: &'static str) -> JResult<JObject> {
    key.obj()?;
    let key = key.clone();
    Ok(lambda(COMPARATOR, move |a| {
        let (x, y) = (key.invoke(method, &[a[0].clone()])?, key.invoke(method, &[a[1].clone()])?);
        Ok(box_i32(x.compare_to(&y)?))
    }))
}

/// `cmp.compare(a, b)`。
pub fn compare(cmp: &JObject, a: impl Into<JObject>, b: impl Into<JObject>) -> JResult<i32> {
    cmp.obj()?;
    compare_with(cmp, &a.into(), &b.into())
}

/// `Function.identity()`。
pub fn identity() -> JObject {
    lambda(&["java.util.function.Function", "java.util.function.UnaryOperator"], |a| Ok(a[0].clone()))
}

/// `f.andThen(g)`（Function）。
pub fn and_then(f: &JObject, g: &JObject) -> JResult<JObject> {
    f.obj()?;
    g.obj()?;
    let (f, g) = (f.clone(), g.clone());
    Ok(lambda(&["java.util.function.Function"], move |a| g.invoke("apply", &[f.invoke("apply", a)?])))
}

/// `f.compose(g)`（Function）。
pub fn compose(f: &JObject, g: &JObject) -> JResult<JObject> {
    and_then(g, f)
}

/// `p.negate()`。
pub fn negate(p: &JObject) -> JResult<JObject> {
    p.obj()?;
    let p = p.clone();
    Ok(lambda(&["java.util.function.Predicate"], move |a| Ok(box_bool(!p.invoke("test", a)?.unbox_bool()?))))
}

/// `p.and(q)`。
pub fn and(p: &JObject, q: &JObject) -> JResult<JObject> {
    p.obj()?;
    q.obj()?;
    let (p, q) = (p.clone(), q.clone());
    Ok(lambda(&["java.util.function.Predicate"], move |a| {
        Ok(box_bool(p.invoke("test", a)?.unbox_bool()? && q.invoke("test", a)?.unbox_bool()?))
    }))
}

/// `p.or(q)`。
pub fn or(p: &JObject, q: &JObject) -> JResult<JObject> {
    p.obj()?;
    q.obj()?;
    let (p, q) = (p.clone(), q.clone());
    Ok(lambda(&["java.util.function.Predicate"], move |a| {
        Ok(box_bool(p.invoke("test", a)?.unbox_bool()? || q.invoke("test", a)?.unbox_bool()?))
    }))
}

/// `Map.Entry.comparingByKey()`。
pub fn comparing_by_key() -> JObject {
    use crate::util::collections::Collections as _;
    lambda(COMPARATOR, |a| Ok(box_i32(a[0].get_key()?.compare_to(&a[1].get_key()?)?)))
}

/// `Map.Entry.comparingByValue()`。
pub fn comparing_by_value() -> JObject {
    use crate::util::collections::Collections as _;
    lambda(COMPARATOR, |a| Ok(box_i32(a[0].get_value()?.compare_to(&a[1].get_value()?)?)))
}
