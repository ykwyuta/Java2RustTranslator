//! java.util.Collections / Objects / List.of などの static メソッド。

use crate::array::JArray;
use crate::lang::string::JString;
use crate::object::{equals_nullable, hash_nullable, JObject};
use crate::rt::throw;
use crate::util::collections::{list_from_vec, new_list, new_set_from, to_vec, Collections as _, ListKind, SetKind};
use crate::util::core::compare_with;

// ------------------------------------------------------------------ List.of / Set.of / Arrays.asList

/// `List.of(a, b, ...)`（引数は配列にまとめて渡す）。
pub fn list_of(items: &JArray<JObject>) -> JObject {
    list_from_vec(ListKind::Immutable, items.to_vec())
}

/// `List.copyOf(collection)`。
pub fn list_copy_of(c: &JObject) -> JObject {
    list_from_vec(ListKind::Immutable, to_vec(c))
}

/// `Arrays.asList(a, b, ...)`（配列への書き戻しはしない）。
pub fn as_list<T: Clone + Into<JObject>>(items: &JArray<T>) -> JObject {
    list_from_vec(ListKind::FixedSize, items.to_vec().into_iter().map(Into::into).collect())
}

/// `Set.of(a, b, ...)`（Java の Set.of は反復順が実行ごとに変わるが、ここでは引数の順にする）。
pub fn set_of(items: &JArray<JObject>) -> JObject {
    new_set_from(SetKind::Immutable, &list_from_vec(ListKind::ArrayList, items.to_vec()))
}

/// `Set.copyOf(collection)`。
pub fn set_copy_of(c: &JObject) -> JObject {
    new_set_from(SetKind::Immutable, c)
}

// ------------------------------------------------------------------ Collections

/// `Collections.sort(list)` / `Collections.sort(list, cmp)`。
pub fn sort(list: &JObject, cmp: &JObject) {
    list.sort(cmp);
}

/// `Collections.reverse(list)`。
pub fn reverse(list: &JObject) {
    let mut v = to_vec(list);
    v.reverse();
    for (i, x) in v.into_iter().enumerate() {
        list.set(i as i32, x);
    }
}

/// `Collections.swap(list, i, j)`。
pub fn swap(list: &JObject, i: i32, j: i32) {
    let a = list.get(i);
    let b = list.set(j, a);
    list.set(i, b);
}

/// `Collections.shuffle(list, rnd)`（Java と同じ手順なので、同じ Random なら同じ結果）。
pub fn shuffle(list: &JObject, rnd: &JObject) {
    let n = list.size();
    let mut i = n;
    while i > 1 {
        let j = crate::util::random::next_int_bound(rnd, i);
        swap(list, i - 1, j);
        i -= 1;
    }
}

/// `Collections.max(coll)` / `Collections.max(coll, cmp)`。
pub fn max(c: &JObject, cmp: &JObject) -> JObject {
    let v = to_vec(c);
    let mut it = v.into_iter();
    let mut best = it.next().unwrap_or_else(|| throw("java.util.NoSuchElementException", None));
    for x in it {
        if compare_with(cmp, &x, &best) > 0 {
            best = x;
        }
    }
    best
}

/// `Collections.min(coll)` / `Collections.min(coll, cmp)`。
pub fn min(c: &JObject, cmp: &JObject) -> JObject {
    let v = to_vec(c);
    let mut it = v.into_iter();
    let mut best = it.next().unwrap_or_else(|| throw("java.util.NoSuchElementException", None));
    for x in it {
        if compare_with(cmp, &x, &best) < 0 {
            best = x;
        }
    }
    best
}

/// `Collections.frequency(coll, x)`。
pub fn frequency(c: &JObject, x: impl Into<JObject>) -> i32 {
    let x = x.into();
    to_vec(c).iter().filter(|e| equals_nullable(&x, e)).count() as i32
}

/// `Collections.nCopies(n, x)`。
pub fn n_copies(n: i32, x: impl Into<JObject>) -> JObject {
    if n < 0 {
        throw("java.lang.IllegalArgumentException", Some(&format!("List length = {n}")));
    }
    let x = x.into();
    list_from_vec(ListKind::Immutable, vec![x; n as usize])
}

/// `Collections.emptyList()`。
pub fn empty_list() -> JObject {
    list_from_vec(ListKind::Immutable, Vec::new())
}

/// `Collections.unmodifiableList(list)`（元のリストの変更は反映されない複製）。
pub fn unmodifiable_list(c: &JObject) -> JObject {
    list_from_vec(ListKind::Immutable, to_vec(c))
}

/// `Collections.singletonList(x)`。
pub fn singleton_list(x: impl Into<JObject>) -> JObject {
    list_from_vec(ListKind::Immutable, vec![x.into()])
}

/// `Collections.addAll(coll, a, b, ...)`。
pub fn add_all_items(c: &JObject, items: &JArray<JObject>) -> bool {
    let mut changed = false;
    for x in items.to_vec() {
        changed |= c.add(x);
    }
    changed
}

/// `Collections.binarySearch(list, key)`。
pub fn binary_search(list: &JObject, key: impl Into<JObject>, cmp: &JObject) -> i32 {
    let key = key.into();
    let v = to_vec(list);
    let (mut lo, mut hi) = (0i32, v.len() as i32 - 1);
    while lo <= hi {
        let mid = ((lo + hi) as u32 >> 1) as i32;
        let c = compare_with(cmp, &v[mid as usize], &key);
        if c < 0 {
            lo = mid + 1;
        } else if c > 0 {
            hi = mid - 1;
        } else {
            return mid;
        }
    }
    -(lo + 1)
}

/// `new ArrayList<>(Arrays.asList(...))` のように、配列から ArrayList を作る。
pub fn array_list_of(items: &JArray<JObject>) -> JObject {
    let l = new_list(ListKind::ArrayList);
    for x in items.to_vec() {
        l.add(x);
    }
    l
}

// ------------------------------------------------------------------ Objects

/// `Objects.equals(a, b)`。
pub fn objects_equals(a: impl Into<JObject>, b: impl Into<JObject>) -> bool {
    equals_nullable(&a.into(), &b.into())
}

/// `Objects.hashCode(o)`。
pub fn objects_hash_code(o: impl Into<JObject>) -> i32 {
    hash_nullable(&o.into())
}

/// `Objects.hash(a, b, ...)` / `Arrays.hashCode(Object[])`。
pub fn objects_hash(items: &JArray<JObject>) -> i32 {
    if items.is_null() {
        return 0;
    }
    items.to_vec().iter().fold(1i32, |h, x| h.wrapping_mul(31).wrapping_add(hash_nullable(x)))
}

/// `Objects.requireNonNull(o)` / `Objects.requireNonNull(o, message)`。
pub fn require_non_null(o: JObject, message: &JString) -> JObject {
    if o.is_null() {
        let m = if message.is_null() { None } else { Some(message.as_str().to_string()) };
        throw("java.lang.NullPointerException", m.as_deref());
    }
    o
}

/// `Objects.toString(o)` / `String.valueOf(Object)`。
pub fn objects_to_string(o: impl Into<JObject>) -> JString {
    let o = o.into();
    if o.is_null() {
        JString::from("null")
    } else {
        o.to_jstring()
    }
}

/// `Objects.requireNonNullElse(o, default)`。
pub fn require_non_null_else(o: JObject, default: JObject) -> JObject {
    if o.is_null() {
        default
    } else {
        o
    }
}

// ------------------------------------------------------------------ Class

struct ClassObj {
    base: crate::object::ObjectBase,
    name: &'static str,
}

impl crate::object::Object for ClassObj {
    fn base(&self) -> &crate::object::ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.lang.Class"
    }
    fn to_jstring(&self) -> JString {
        JString::from(format!("class {}", self.name))
    }
    fn equals(&self, other: &JObject) -> bool {
        other.downcast_ref::<ClassObj>().is_some_and(|o| o.name == self.name)
    }
    fn hash_code(&self) -> i32 {
        JString::from(self.name).hash_code()
    }
}

/// `o.getClass()`（名前だけを持つ Class オブジェクト）。
pub fn class_of(o: &JObject) -> JObject {
    let name = o.class_name();
    crate::object::alloc(|base| ClassObj { base, name })
}

/// `c.getName()`。
pub fn class_get_name(c: &JObject) -> JString {
    match c.downcast_ref::<ClassObj>() {
        Some(k) => JString::from(k.name),
        None => crate::object::bad_cast(c, "java.lang.Class"),
    }
}

/// `c.getSimpleName()`。
pub fn class_get_simple_name(c: &JObject) -> JString {
    let n = class_get_name(c);
    let s = n.as_str();
    JString::from(s.rsplit(['.', '$']).next().unwrap_or(s))
}

/// `System.identityHashCode(o)`。
pub fn identity_hash_code(o: &JObject) -> i32 {
    if o.is_null() {
        0
    } else {
        o.obj().base().identity_hash()
    }
}
