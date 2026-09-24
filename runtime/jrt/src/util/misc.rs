//! java.util.Collections / Objects / List.of などの static メソッド。

use crate::array::JArray;
use crate::lang::string::JString;
use crate::object::{equals_nullable, hash_nullable, JObject};
use crate::rt::{throw, JResult};
use crate::util::collections::{list_from_vec, unmodifiable_from_vec, new_list, new_set_from, to_vec, Collections as _, ListKind, SetKind};
use crate::util::core::compare_with;

// ------------------------------------------------------------------ List.of / Set.of / Arrays.asList

/// `List.of(a, b, ...)`（引数は配列にまとめて渡す）。
pub fn list_of(items: &JArray<JObject>) -> JResult<JObject> {
    list_from_vec(ListKind::Immutable, items.to_vec()?)
}

/// `List.copyOf(collection)`。
pub fn list_copy_of(c: &JObject) -> JResult<JObject> {
    list_from_vec(ListKind::Immutable, to_vec(c)?)
}

/// `Arrays.asList(a, b, ...)`（配列への書き戻しはしない）。
pub fn as_list<T: Clone + Into<JObject>>(items: &JArray<T>) -> JResult<JObject> {
    list_from_vec(ListKind::FixedSize, items.to_vec()?.into_iter().map(Into::into).collect())
}

/// `Set.of(a, b, ...)`（Java の Set.of は反復順が実行ごとに変わるが、ここでは引数の順にする）。
pub fn set_of(items: &JArray<JObject>) -> JResult<JObject> {
    new_set_from(SetKind::Immutable, &list_from_vec(ListKind::ArrayList, items.to_vec()?)?)
}

/// `Set.copyOf(collection)`。
pub fn set_copy_of(c: &JObject) -> JResult<JObject> {
    new_set_from(SetKind::Immutable, c)
}

// ------------------------------------------------------------------ Collections

/// `Collections.sort(list)` / `Collections.sort(list, cmp)`。
pub fn sort(list: &JObject, cmp: &JObject) -> JResult<()> {
    list.sort(cmp)
}

/// `Collections.reverse(list)`。
pub fn reverse(list: &JObject) -> JResult<()> {
    let mut v = to_vec(list)?;
    v.reverse();
    for (i, x) in v.into_iter().enumerate() {
        list.set(i as i32, x)?;
    }
    Ok(())
}

/// `Collections.swap(list, i, j)`。
pub fn swap(list: &JObject, i: i32, j: i32) -> JResult<()> {
    let a = list.get(i)?;
    let b = list.set(j, a)?;
    list.set(i, b)?;
    Ok(())
}

/// `Collections.shuffle(list, rnd)`（Java と同じ手順なので、同じ Random なら同じ結果）。
pub fn shuffle(list: &JObject, rnd: &JObject) -> JResult<()> {
    let n = list.size()?;
    let mut i = n;
    while i > 1 {
        let j = crate::util::random::next_int_bound(rnd, i)?;
        swap(list, i - 1, j)?;
        i -= 1;
    }
    Ok(())
}

fn extreme(c: &JObject, cmp: &JObject, want: std::cmp::Ordering) -> JResult<JObject> {
    let mut it = to_vec(c)?.into_iter();
    let Some(mut best) = it.next() else { return throw("java.util.NoSuchElementException", None) };
    for x in it {
        if compare_with(cmp, &x, &best)?.cmp(&0) == want {
            best = x;
        }
    }
    Ok(best)
}

/// `Collections.max(coll)` / `Collections.max(coll, cmp)`。
pub fn max(c: &JObject, cmp: &JObject) -> JResult<JObject> {
    extreme(c, cmp, std::cmp::Ordering::Greater)
}

/// `Collections.min(coll)` / `Collections.min(coll, cmp)`。
pub fn min(c: &JObject, cmp: &JObject) -> JResult<JObject> {
    extreme(c, cmp, std::cmp::Ordering::Less)
}

/// `Collections.frequency(coll, x)`。
pub fn frequency(c: &JObject, x: impl Into<JObject>) -> JResult<i32> {
    let x = x.into();
    let mut n = 0;
    for e in to_vec(c)? {
        if equals_nullable(&x, &e)? {
            n += 1;
        }
    }
    Ok(n)
}

/// `Collections.nCopies(n, x)`。
pub fn n_copies(n: i32, x: impl Into<JObject>) -> JResult<JObject> {
    if n < 0 {
        return throw("java.lang.IllegalArgumentException", Some(&format!("List length = {n}")));
    }
    let x = x.into();
    Ok(unmodifiable_from_vec(vec![x; n as usize]))
}

/// `Collections.emptyList()`。
pub fn empty_list() -> JResult<JObject> {
    list_from_vec(ListKind::Immutable, Vec::new())
}

/// `Collections.unmodifiableList(list)`（元のリストの変更は反映されない複製）。
pub fn unmodifiable_list(c: &JObject) -> JResult<JObject> {
    Ok(unmodifiable_from_vec(to_vec(c)?))
}

/// `Collections.singletonList(x)`。
pub fn singleton_list(x: impl Into<JObject>) -> JResult<JObject> {
    Ok(unmodifiable_from_vec(vec![x.into()]))
}

/// `Collections.addAll(coll, a, b, ...)`。
pub fn add_all_items(c: &JObject, items: &JArray<JObject>) -> JResult<bool> {
    let mut changed = false;
    for x in items.to_vec()? {
        changed |= c.add(x)?;
    }
    Ok(changed)
}

/// `Collections.binarySearch(list, key)`。
pub fn binary_search(list: &JObject, key: impl Into<JObject>, cmp: &JObject) -> JResult<i32> {
    let key = key.into();
    let v = to_vec(list)?;
    let (mut lo, mut hi) = (0i32, v.len() as i32 - 1);
    while lo <= hi {
        let mid = ((lo + hi) as u32 >> 1) as i32;
        let c = compare_with(cmp, &v[mid as usize], &key)?;
        if c < 0 {
            lo = mid + 1;
        } else if c > 0 {
            hi = mid - 1;
        } else {
            return Ok(mid);
        }
    }
    Ok(-(lo + 1))
}

/// `new ArrayList<>(Arrays.asList(...))` のように、配列から ArrayList を作る。
pub fn array_list_of(items: &JArray<JObject>) -> JResult<JObject> {
    let l = new_list(ListKind::ArrayList);
    for x in items.to_vec()? {
        l.add(x)?;
    }
    Ok(l)
}

// ------------------------------------------------------------------ Objects

/// `Objects.equals(a, b)`。
pub fn objects_equals(a: impl Into<JObject>, b: impl Into<JObject>) -> JResult<bool> {
    equals_nullable(&a.into(), &b.into())
}

/// `Objects.hashCode(o)`。
pub fn objects_hash_code(o: impl Into<JObject>) -> JResult<i32> {
    hash_nullable(&o.into())
}

/// `Objects.hash(a, b, ...)` / `Arrays.hashCode(Object[])`。
pub fn objects_hash(items: &JArray<JObject>) -> JResult<i32> {
    if items.is_null() {
        return Ok(0);
    }
    let mut h = 1i32;
    for x in items.to_vec()? {
        h = h.wrapping_mul(31).wrapping_add(hash_nullable(&x)?);
    }
    Ok(h)
}

/// `Objects.requireNonNull(o)` / `Objects.requireNonNull(o, message)`。
pub fn require_non_null(o: JObject, message: &JString) -> JResult<JObject> {
    if o.is_null() {
        return throw("java.lang.NullPointerException", message.opt_str());
    }
    Ok(o)
}

/// `Objects.toString(o)` / `String.valueOf(Object)`。
pub fn objects_to_string(o: impl Into<JObject>) -> JResult<JString> {
    JString::value_of_object(&o.into())
}

/// `Objects.requireNonNullElse(o, default)`。
pub fn require_non_null_else(o: JObject, default: JObject) -> JResult<JObject> {
    if !o.is_null() {
        return Ok(o);
    }
    if default.is_null() {
        return throw("java.lang.NullPointerException", Some("defaultObj"));
    }
    Ok(default)
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
    fn to_jstring(&self) -> JResult<JString> {
        Ok(JString::from(format!("class {}", self.name)))
    }
    fn equals(&self, other: &JObject) -> JResult<bool> {
        Ok(other.downcast_ref::<ClassObj>().is_some_and(|o| o.name == self.name))
    }
    fn hash_code(&self) -> JResult<i32> {
        JString::from(self.name).hash_code()
    }
}

/// `o.getClass()`（名前だけを持つ Class オブジェクト）。
pub fn class_of(o: &JObject) -> JResult<JObject> {
    Ok(class_for(o.class_name()?))
}

thread_local! {
    static CLASSES: std::cell::RefCell<std::collections::HashMap<&'static str, JObject>> = std::cell::RefCell::new(std::collections::HashMap::new());
}

/// クラス名（binary name）の Class オブジェクト（`X.class`。同じ名前なら同じオブジェクト）。
pub fn class_for(name: &'static str) -> JObject {
    CLASSES.with(|c| c.borrow_mut().entry(name).or_insert_with(|| crate::object::alloc(|base| ClassObj { base, name })).clone())
}

/// `c.getName()`。
pub fn class_get_name(c: &JObject) -> JResult<JString> {
    match c.downcast_ref::<ClassObj>() {
        Some(k) => Ok(JString::from(k.name)),
        None => {
            c.obj()?;
            crate::object::class_cast(c, "java.lang.Class")
        }
    }
}

/// `c.getSimpleName()`。
pub fn class_get_simple_name(c: &JObject) -> JResult<JString> {
    let n = class_get_name(c)?;
    let s = n.as_str_or_null();
    Ok(JString::from(s.rsplit(['.', '$']).next().unwrap_or(s)))
}

/// `System.identityHashCode(o)`。
pub fn identity_hash_code(o: &JObject) -> i32 {
    match o.obj() {
        Ok(x) => x.base().identity_hash(),
        Err(_) => 0,
    }
}

