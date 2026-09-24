//! java.util.function と Comparator の既定メソッド・static メソッド。

use crate::lambda::lambda;
use crate::lang::boxed::{box_bool, box_i32};
use crate::object::JObject;
use crate::util::core::compare_with;

const COMPARATOR: &[&str] = &["java.util.Comparator"];

/// `Comparator.naturalOrder()`。
pub fn natural_order() -> JObject {
    lambda(COMPARATOR, |a| box_i32(a[0].compare_to(&a[1])))
}

/// `Comparator.reverseOrder()` / `Collections.reverseOrder()`。
pub fn reverse_order() -> JObject {
    lambda(COMPARATOR, |a| box_i32(a[1].compare_to(&a[0])))
}

/// `cmp.reversed()`。
pub fn reversed(cmp: &JObject) -> JObject {
    let cmp = cmp.clone();
    lambda(COMPARATOR, move |a| box_i32(compare_with(&cmp, &a[1], &a[0])))
}

/// `cmp.thenComparing(other)`。
pub fn then_comparing(cmp: &JObject, other: &JObject) -> JObject {
    let (cmp, other) = (cmp.clone(), other.clone());
    lambda(COMPARATOR, move |a| {
        let c = compare_with(&cmp, &a[0], &a[1]);
        box_i32(if c != 0 { c } else { compare_with(&other, &a[0], &a[1]) })
    })
}

/// `cmp.thenComparing(keyExtractor)`。
pub fn then_comparing_key(cmp: &JObject, key: &JObject) -> JObject {
    then_comparing(cmp, &comparing(key))
}

/// `Comparator.comparing(keyExtractor)`。
pub fn comparing(key: &JObject) -> JObject {
    let key = key.clone();
    lambda(COMPARATOR, move |a| {
        let (x, y) = (key.invoke("apply", &[a[0].clone()]), key.invoke("apply", &[a[1].clone()]));
        box_i32(x.compare_to(&y))
    })
}

/// `Comparator.comparing(keyExtractor, keyComparator)`。
pub fn comparing_with(key: &JObject, key_cmp: &JObject) -> JObject {
    let (key, key_cmp) = (key.clone(), key_cmp.clone());
    lambda(COMPARATOR, move |a| {
        let (x, y) = (key.invoke("apply", &[a[0].clone()]), key.invoke("apply", &[a[1].clone()]));
        box_i32(compare_with(&key_cmp, &x, &y))
    })
}

/// `Comparator.comparingInt` / `comparingLong` / `comparingDouble`（キーはボクシングされた数値）。
pub fn comparing_number(key: &JObject, method: &'static str) -> JObject {
    let key = key.clone();
    lambda(COMPARATOR, move |a| {
        let (x, y) = (key.invoke(method, &[a[0].clone()]), key.invoke(method, &[a[1].clone()]));
        box_i32(x.compare_to(&y))
    })
}

/// `cmp.compare(a, b)`。
pub fn compare(cmp: &JObject, a: impl Into<JObject>, b: impl Into<JObject>) -> i32 {
    compare_with(cmp, &a.into(), &b.into())
}

/// `Function.identity()`。
pub fn identity() -> JObject {
    lambda(&["java.util.function.Function", "java.util.function.UnaryOperator"], |a| a[0].clone())
}

/// `f.andThen(g)`（Function）。
pub fn and_then(f: &JObject, g: &JObject) -> JObject {
    let (f, g) = (f.clone(), g.clone());
    lambda(&["java.util.function.Function"], move |a| g.invoke("apply", &[f.invoke("apply", a)]))
}

/// `f.compose(g)`（Function）。
pub fn compose(f: &JObject, g: &JObject) -> JObject {
    and_then(g, f)
}

/// `p.negate()`。
pub fn negate(p: &JObject) -> JObject {
    let p = p.clone();
    lambda(&["java.util.function.Predicate"], move |a| box_bool(!p.invoke("test", a).unbox_bool()))
}

/// `p.and(q)`。
pub fn and(p: &JObject, q: &JObject) -> JObject {
    let (p, q) = (p.clone(), q.clone());
    lambda(&["java.util.function.Predicate"], move |a| box_bool(p.invoke("test", a).unbox_bool() && q.invoke("test", a).unbox_bool()))
}

/// `p.or(q)`。
pub fn or(p: &JObject, q: &JObject) -> JObject {
    let (p, q) = (p.clone(), q.clone());
    lambda(&["java.util.function.Predicate"], move |a| box_bool(p.invoke("test", a).unbox_bool() || q.invoke("test", a).unbox_bool()))
}

/// `Map.Entry.comparingByKey()`。
pub fn comparing_by_key() -> JObject {
    use crate::util::collections::Collections as _;
    lambda(COMPARATOR, |a| box_i32(a[0].get_key().compare_to(&a[1].get_key())))
}

/// `Map.Entry.comparingByValue()`。
pub fn comparing_by_value() -> JObject {
    use crate::util::collections::Collections as _;
    lambda(COMPARATOR, |a| box_i32(a[0].get_value().compare_to(&a[1].get_value())))
}
