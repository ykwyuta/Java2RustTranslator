//! java.util のコレクション（List / Deque / Set / Map / PriorityQueue）と Iterator。
//!
//! どのコレクションも `JObject` として扱い、操作は [`Collections`] トレイトのメソッド（`list.add(x)` など）で行う。
//! 実行時の型で振り分けるので、`List` 型の変数に ArrayList と LinkedList のどちらが入っていても同じコードで動く。
//! ユーザーのクラスがコレクションのインタフェースを実装している場合は、`Object::invoke` でそのメソッドを呼ぶ。
//! 要素の equals / hashCode / compareTo や比較関数はユーザーのコードで例外を送出しうるので、操作は [`JResult`] を返す。

use crate::lang::string::JString;
use crate::lang::stringify::to_java_string;
use crate::object::{alloc, equals_nullable, hash_nullable, JObject, Object, ObjectBase};
use crate::rt::{npe, throw, JResult};
use crate::util::core::{compare_with, new_entry, spread, table_size_for, try_sort_by, HashCore, MapEntry, TreeCore};
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::rc::Rc;

fn unsupported<T>() -> JResult<T> {
    throw("java.lang.UnsupportedOperationException", None)
}

fn no_such_element<T>() -> JResult<T> {
    throw("java.util.NoSuchElementException", None)
}

fn index_oob<T>(index: i32, len: usize) -> JResult<T> {
    throw(
        "java.lang.IndexOutOfBoundsException",
        Some(&format!("Index {index} out of bounds for length {len}")),
    )
}

fn join(items: Vec<String>, open: &str, close: &str) -> JString {
    let mut s = String::from(open);
    for (i, x) in items.iter().enumerate() {
        if i > 0 {
            s.push_str(", ");
        }
        s.push_str(x);
    }
    s.push_str(close);
    JString::from(s)
}

/// 要素の文字列化（自分自身を含むコレクションは Java と同じく "(this Collection)"）。
fn show_all(this: &JObject, items: &[JObject]) -> JResult<Vec<String>> {
    let mut out = Vec::with_capacity(items.len());
    for x in items {
        out.push(if x.same(this) { "(this Collection)".to_string() } else { to_java_string(x)? });
    }
    Ok(out)
}

fn index_of_in(items: &[JObject], x: &JObject) -> JResult<Option<usize>> {
    for (i, e) in items.iter().enumerate() {
        if equals_nullable(x, e)? {
            return Ok(Some(i));
        }
    }
    Ok(None)
}

// ================================================================== List / Deque

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ListKind {
    ArrayList,
    LinkedList,
    ArrayDeque,
    Stack,
    /// List.of / Collections.unmodifiableList など（変更すると UnsupportedOperationException）。
    Immutable,
    /// Arrays.asList（要素の set はできるが、追加・削除はできない）。
    FixedSize,
    /// Map.keySet / values / entrySet の結果（要素の削除だけがマップに反映される）。
    KeySet,
    Values,
    EntrySet,
}

pub struct JList {
    base: ObjectBase,
    kind: ListKind,
    items: RefCell<VecDeque<JObject>>,
    /// ビューの元になったマップ。
    backing: JObject,
}

impl JList {
    fn check_modifiable(&self) -> JResult<()> {
        if matches!(self.kind, ListKind::Immutable | ListKind::FixedSize | ListKind::KeySet | ListKind::Values | ListKind::EntrySet) {
            return unsupported();
        }
        Ok(())
    }

    fn is_list(&self) -> bool {
        !matches!(self.kind, ListKind::ArrayDeque | ListKind::KeySet | ListKind::Values | ListKind::EntrySet)
    }

    fn snapshot(&self) -> Vec<JObject> {
        self.items.borrow().iter().cloned().collect()
    }
}

impl Object for JList {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        match self.kind {
            ListKind::ArrayList => "java.util.ArrayList",
            ListKind::LinkedList => "java.util.LinkedList",
            ListKind::ArrayDeque => "java.util.ArrayDeque",
            ListKind::Stack => "java.util.Stack",
            ListKind::Immutable => "java.util.ImmutableCollections$ListN",
            ListKind::FixedSize => "java.util.Arrays$ArrayList",
            ListKind::KeySet => "java.util.HashMap$KeySet",
            ListKind::Values => "java.util.HashMap$Values",
            ListKind::EntrySet => "java.util.HashMap$EntrySet",
        }
    }
    fn instance_of(&self, class: &str) -> bool {
        if matches!(class, "java.lang.Object" | "java.util.Collection" | "java.lang.Iterable") || class == self.class_name() {
            return true;
        }
        match self.kind {
            ListKind::ArrayDeque => matches!(class, "java.util.Deque" | "java.util.Queue" | "java.util.SequencedCollection"),
            ListKind::KeySet | ListKind::EntrySet => class == "java.util.Set",
            ListKind::Values => false,
            ListKind::LinkedList => matches!(class, "java.util.List" | "java.util.Deque" | "java.util.Queue" | "java.util.SequencedCollection" | "java.util.AbstractList"),
            ListKind::Stack => matches!(class, "java.util.List" | "java.util.Vector" | "java.util.RandomAccess" | "java.util.SequencedCollection" | "java.util.AbstractList"),
            _ => matches!(class, "java.util.List" | "java.util.RandomAccess" | "java.util.SequencedCollection" | "java.util.AbstractList"),
        }
    }
    fn to_jstring(&self) -> JResult<JString> {
        Ok(join(show_all(&self.base().this(), &self.snapshot())?, "[", "]"))
    }
    fn equals(&self, other: &JObject) -> JResult<bool> {
        if other.same(&self.base().this()) {
            return Ok(true);
        }
        match self.kind {
            ListKind::ArrayDeque => Ok(false),
            ListKind::KeySet | ListKind::EntrySet => set_equals(&self.base().this(), other),
            ListKind::Values => Ok(false),
            _ => match other.downcast_ref::<JList>() {
                Some(o) if o.is_list() => {
                    let (a, b) = (self.snapshot(), o.snapshot());
                    if a.len() != b.len() {
                        return Ok(false);
                    }
                    for (x, y) in a.iter().zip(b.iter()) {
                        if !equals_nullable(x, y)? {
                            return Ok(false);
                        }
                    }
                    Ok(true)
                }
                _ => Ok(false),
            },
        }
    }
    fn hash_code(&self) -> JResult<i32> {
        match self.kind {
            ListKind::ArrayDeque | ListKind::Values => Ok(self.base().identity_hash()),
            ListKind::KeySet | ListKind::EntrySet => {
                let mut h = 0i32;
                for x in self.snapshot() {
                    h = h.wrapping_add(hash_nullable(&x)?);
                }
                Ok(h)
            }
            _ => {
                let mut h = 1i32;
                for x in self.snapshot() {
                    h = h.wrapping_mul(31).wrapping_add(hash_nullable(&x)?);
                }
                Ok(h)
            }
        }
    }
}

fn new_list_of(kind: ListKind, items: VecDeque<JObject>, backing: JObject) -> JObject {
    alloc(|base| JList { base, kind, items: RefCell::new(items), backing })
}

/// `new ArrayList<>()` など。
pub fn new_list(kind: ListKind) -> JObject {
    new_list_of(kind, VecDeque::new(), JObject::null())
}

/// `new ArrayList<>(initialCapacity)`（負なら IllegalArgumentException）。
pub fn new_list_with_capacity(kind: ListKind, cap: i32) -> JResult<JObject> {
    if cap < 0 {
        return throw("java.lang.IllegalArgumentException", Some(&format!("Illegal Capacity: {cap}")));
    }
    Ok(new_list(kind))
}

/// `new ArrayList<>(collection)` など。
pub fn new_list_from(kind: ListKind, source: &JObject) -> JResult<JObject> {
    Ok(new_list_of(kind, to_vec(source)?.into(), JObject::null()))
}

/// 要素の配列からリストを作る（List.of / Arrays.asList / Collections.unmodifiableList）。
pub fn list_from_vec(kind: ListKind, items: Vec<JObject>) -> JResult<JObject> {
    if kind == ListKind::Immutable && items.iter().any(JObject::is_null) {
        return npe();
    }
    Ok(new_list_of(kind, items.into(), JObject::null()))
}

/// 変更できないリスト（Collections.unmodifiableList / nCopies / singletonList。List.of と違って null を許す）。
pub fn unmodifiable_from_vec(items: Vec<JObject>) -> JObject {
    new_list_of(ListKind::Immutable, items.into(), JObject::null())
}

// ================================================================== Set

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum SetKind {
    Hash,
    LinkedHash,
    Tree,
    Immutable,
}

enum SetImpl {
    Hash(RefCell<HashCore>),
    Tree(RefCell<TreeCore>),
}

pub struct JSet {
    base: ObjectBase,
    kind: SetKind,
    imp: SetImpl,
}

impl JSet {
    fn elements(&self) -> Vec<JObject> {
        match &self.imp {
            SetImpl::Hash(c) => c.borrow().entries().iter().map(|e| e.key.clone()).collect(),
            SetImpl::Tree(c) => c.borrow().entries.iter().map(|e| e.key.clone()).collect(),
        }
    }

    fn contains(&self, x: &JObject) -> JResult<bool> {
        match &self.imp {
            SetImpl::Hash(c) => {
                let h = spread(x)?;
                Ok(c.borrow().find(x, h)?.is_some())
            }
            SetImpl::Tree(c) => Ok(c.borrow().search(x)?.is_ok()),
        }
    }

    fn add(&self, x: JObject) -> JResult<bool> {
        if self.kind == SetKind::Immutable {
            return unsupported();
        }
        self.insert(x)
    }

    fn insert(&self, x: JObject) -> JResult<bool> {
        match &self.imp {
            SetImpl::Hash(c) => {
                let h = spread(&x)?;
                if c.borrow().find(&x, h)?.is_some() {
                    return Ok(false);
                }
                c.borrow_mut().insert_new(MapEntry::new(h, x, JObject::null()));
                Ok(true)
            }
            SetImpl::Tree(c) => {
                if c.borrow().entries.is_empty() {
                    // 最初の要素も比較可能かを確かめる（Java の TreeMap.put と同じ）。
                    let cmp = c.borrow().comparator.clone();
                    compare_with(&cmp, &x, &x)?;
                }
                let pos = c.borrow().search(&x)?;
                match pos {
                    Ok(_) => Ok(false),
                    Err(i) => {
                        c.borrow_mut().entries.insert(i, MapEntry::new(0, x, JObject::null()));
                        Ok(true)
                    }
                }
            }
        }
    }

    fn remove(&self, x: &JObject) -> JResult<bool> {
        if self.kind == SetKind::Immutable {
            return unsupported();
        }
        match &self.imp {
            SetImpl::Hash(c) => {
                let h = spread(x)?;
                let found = c.borrow().find(x, h)?;
                match found {
                    Some(e) => {
                        c.borrow_mut().remove_entry(&e);
                        Ok(true)
                    }
                    None => Ok(false),
                }
            }
            SetImpl::Tree(c) => {
                let pos = c.borrow().search(x)?;
                match pos {
                    Ok(i) => {
                        c.borrow_mut().entries.remove(i);
                        Ok(true)
                    }
                    Err(_) => Ok(false),
                }
            }
        }
    }

    fn len(&self) -> usize {
        match &self.imp {
            SetImpl::Hash(c) => c.borrow().len(),
            SetImpl::Tree(c) => c.borrow().entries.len(),
        }
    }

    fn clear(&self) -> JResult<()> {
        if self.kind == SetKind::Immutable {
            return unsupported();
        }
        match &self.imp {
            SetImpl::Hash(c) => c.borrow_mut().clear(),
            SetImpl::Tree(c) => c.borrow_mut().entries.clear(),
        }
        Ok(())
    }

    fn tree(&self) -> JResult<&RefCell<TreeCore>> {
        match &self.imp {
            SetImpl::Tree(c) => Ok(c),
            SetImpl::Hash(_) => unsupported(),
        }
    }
}

impl Object for JSet {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        match self.kind {
            SetKind::Hash => "java.util.HashSet",
            SetKind::LinkedHash => "java.util.LinkedHashSet",
            SetKind::Tree => "java.util.TreeSet",
            SetKind::Immutable => "java.util.ImmutableCollections$SetN",
        }
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.Collection" | "java.lang.Iterable" | "java.util.Set")
            || class == self.class_name()
            || (self.kind == SetKind::Tree && matches!(class, "java.util.SortedSet" | "java.util.NavigableSet" | "java.util.SequencedSet" | "java.util.SequencedCollection"))
            || (self.kind == SetKind::LinkedHash && matches!(class, "java.util.HashSet" | "java.util.SequencedSet" | "java.util.SequencedCollection"))
    }
    fn to_jstring(&self) -> JResult<JString> {
        Ok(join(show_all(&self.base().this(), &self.elements())?, "[", "]"))
    }
    fn equals(&self, other: &JObject) -> JResult<bool> {
        set_equals(&self.base().this(), other)
    }
    fn hash_code(&self) -> JResult<i32> {
        let mut h = 0i32;
        for x in self.elements() {
            h = h.wrapping_add(hash_nullable(&x)?);
        }
        Ok(h)
    }
}

fn set_equals(this: &JObject, other: &JObject) -> JResult<bool> {
    if this.same(other) {
        return Ok(true);
    }
    if !other.instance_of("java.util.Set") {
        return Ok(false);
    }
    let (a, b) = (to_vec(this)?, to_vec(other)?);
    if a.len() != b.len() {
        return Ok(false);
    }
    for x in &b {
        if !contains_any(this, x)? {
            return Ok(false);
        }
    }
    Ok(true)
}

/// `new HashSet<>()` など。
pub fn new_set(kind: SetKind) -> JObject {
    new_set_with(kind, JObject::null(), HashCore::new(kind != SetKind::Hash))
}

/// `new HashSet<>(initialCapacity)`。
pub fn new_set_with_capacity(kind: SetKind, cap: i32) -> JResult<JObject> {
    Ok(new_set_with(kind, JObject::null(), HashCore::with_capacity(kind != SetKind::Hash, cap)?))
}

/// `new TreeSet<>(comparator)`。
pub fn new_tree_set(comparator: JObject) -> JObject {
    new_set_with(SetKind::Tree, comparator, HashCore::new(false))
}

fn new_set_with(kind: SetKind, comparator: JObject, hash: HashCore) -> JObject {
    let imp = match kind {
        SetKind::Tree => SetImpl::Tree(RefCell::new(TreeCore::new(comparator))),
        _ => SetImpl::Hash(RefCell::new(hash)),
    };
    alloc(|base| JSet { base, kind, imp })
}

/// `new HashSet<>(collection)` など。
pub fn new_set_from(kind: SetKind, source: &JObject) -> JResult<JObject> {
    let items = to_vec(source)?;
    let comparator = if kind == SetKind::Tree {
        source.downcast_ref::<JSet>().and_then(|s| match &s.imp {
            SetImpl::Tree(t) => Some(t.borrow().comparator.clone()),
            _ => None,
        }).unwrap_or_default()
    } else {
        JObject::null()
    };
    let real = if kind == SetKind::Immutable { SetKind::LinkedHash } else { kind };
    let s = new_set_with(real, comparator, HashCore::new(real != SetKind::Hash));
    if let Some(set) = s.downcast_ref::<JSet>() {
        if let SetImpl::Hash(c) = &set.imp {
            // HashSet(Collection) は max(n / .75 + 1, 16) の容量で作る。
            let cap = ((items.len() as f64 / 0.75) as usize + 1).max(16);
            c.borrow_mut().presize(table_size_for(cap) * 3 / 4);
        }
        for x in items {
            if kind == SetKind::Immutable && x.is_null() {
                return npe();
            }
            let added = set.insert(x)?;
            if kind == SetKind::Immutable && !added {
                return throw("java.lang.IllegalArgumentException", Some("duplicate element"));
            }
        }
    }
    if kind == SetKind::Immutable {
        return Ok(freeze_set(s));
    }
    Ok(s)
}

fn freeze_set(s: JObject) -> JObject {
    let set = s.downcast_rc::<JSet>().expect("internal error: not a set");
    let imp = match &set.imp {
        SetImpl::Hash(c) => SetImpl::Hash(RefCell::new(std::mem::replace(&mut *c.borrow_mut(), HashCore::new(true)))),
        SetImpl::Tree(_) => unreachable!(),
    };
    alloc(|base| JSet { base, kind: SetKind::Immutable, imp })
}

// ================================================================== Map

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum MapKind {
    Hash,
    LinkedHash,
    Tree,
    Immutable,
}

enum MapImpl {
    Hash(RefCell<HashCore>),
    Tree(RefCell<TreeCore>),
}

pub struct JMap {
    base: ObjectBase,
    kind: MapKind,
    imp: MapImpl,
}

impl JMap {
    fn entries(&self) -> Vec<Rc<MapEntry>> {
        match &self.imp {
            MapImpl::Hash(c) => c.borrow().entries(),
            MapImpl::Tree(c) => c.borrow().entries.clone(),
        }
    }

    fn find(&self, key: &JObject) -> JResult<Option<Rc<MapEntry>>> {
        match &self.imp {
            MapImpl::Hash(c) => {
                let h = spread(key)?;
                c.borrow().find(key, h)
            }
            MapImpl::Tree(c) => {
                let c = c.borrow();
                Ok(c.search(key)?.ok().map(|i| c.entries[i].clone()))
            }
        }
    }

    fn check_modifiable(&self) -> JResult<()> {
        if self.kind == MapKind::Immutable {
            return unsupported();
        }
        Ok(())
    }

    /// 戻り値は以前の値（なければ null）。
    fn put(&self, key: JObject, value: JObject) -> JResult<JObject> {
        self.check_modifiable()?;
        self.insert(key, value)
    }

    fn insert(&self, key: JObject, value: JObject) -> JResult<JObject> {
        match &self.imp {
            MapImpl::Hash(c) => {
                let h = spread(&key)?;
                let found = c.borrow().find(&key, h)?;
                if let Some(e) = found {
                    return Ok(e.set_value(value));
                }
                c.borrow_mut().insert_new(MapEntry::new(h, key, value));
            }
            MapImpl::Tree(c) => {
                if c.borrow().entries.is_empty() {
                    let cmp = c.borrow().comparator.clone();
                    compare_with(&cmp, &key, &key)?;
                }
                let pos = c.borrow().search(&key)?;
                match pos {
                    Ok(i) => {
                        let e = c.borrow().entries[i].clone();
                        return Ok(e.set_value(value));
                    }
                    Err(i) => c.borrow_mut().entries.insert(i, MapEntry::new(0, key, value)),
                }
            }
        }
        Ok(JObject::null())
    }

    /// merge / compute / computeIfAbsent の冒頭処理（HashMap の表の事前拡張）。
    fn begin_compute(&self) -> JResult<()> {
        self.check_modifiable()?;
        if let MapImpl::Hash(c) = &self.imp {
            c.borrow_mut().pre_resize_for_compute();
        }
        Ok(())
    }

    /// merge / compute 系で新しいキーを入れる（HashMap ではバケットの先頭）。
    fn insert_computed(&self, key: JObject, value: JObject) -> JResult<()> {
        match &self.imp {
            MapImpl::Hash(c) => {
                let h = spread(&key)?;
                c.borrow_mut().insert_new_head(MapEntry::new(h, key, value));
            }
            MapImpl::Tree(_) => {
                self.put(key, value)?;
            }
        }
        Ok(())
    }

    fn remove(&self, key: &JObject) -> JResult<Option<Rc<MapEntry>>> {
        self.check_modifiable()?;
        match &self.imp {
            MapImpl::Hash(c) => {
                let h = spread(key)?;
                let found = c.borrow().find(key, h)?;
                if let Some(e) = &found {
                    c.borrow_mut().remove_entry(e);
                }
                Ok(found)
            }
            MapImpl::Tree(c) => {
                let pos = c.borrow().search(key)?;
                Ok(pos.ok().map(|i| c.borrow_mut().entries.remove(i)))
            }
        }
    }

    fn len(&self) -> usize {
        match &self.imp {
            MapImpl::Hash(c) => c.borrow().len(),
            MapImpl::Tree(c) => c.borrow().entries.len(),
        }
    }

    fn tree(&self) -> JResult<&RefCell<TreeCore>> {
        match &self.imp {
            MapImpl::Tree(c) => Ok(c),
            MapImpl::Hash(_) => unsupported(),
        }
    }
}

impl Object for JMap {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        match self.kind {
            MapKind::Hash => "java.util.HashMap",
            MapKind::LinkedHash => "java.util.LinkedHashMap",
            MapKind::Tree => "java.util.TreeMap",
            MapKind::Immutable => "java.util.ImmutableCollections$MapN",
        }
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.Map")
            || class == self.class_name()
            || (self.kind == MapKind::Tree && matches!(class, "java.util.SortedMap" | "java.util.NavigableMap" | "java.util.SequencedMap"))
            || (self.kind == MapKind::LinkedHash && matches!(class, "java.util.HashMap" | "java.util.SequencedMap"))
    }
    fn to_jstring(&self) -> JResult<JString> {
        let this = self.base().this();
        let mut parts = Vec::new();
        for e in self.entries() {
            let show = |x: &JObject| -> JResult<String> {
                if x.same(&this) { Ok("(this Map)".to_string()) } else { to_java_string(x) }
            };
            parts.push(format!("{}={}", show(&e.key)?, show(&e.value())?));
        }
        Ok(join(parts, "{", "}"))
    }
    fn equals(&self, other: &JObject) -> JResult<bool> {
        if other.same(&self.base().this()) {
            return Ok(true);
        }
        let Some(o) = other.downcast_ref::<JMap>() else { return Ok(false) };
        if o.len() != self.len() {
            return Ok(false);
        }
        for e in self.entries() {
            match o.find(&e.key)? {
                Some(x) if equals_nullable(&x.value(), &e.value())? => {}
                _ => return Ok(false),
            }
        }
        Ok(true)
    }
    fn hash_code(&self) -> JResult<i32> {
        let mut h = 0i32;
        for e in self.entries() {
            h = h.wrapping_add(hash_nullable(&e.key)? ^ hash_nullable(&e.value())?);
        }
        Ok(h)
    }
}

/// `new HashMap<>()` など。
pub fn new_map(kind: MapKind) -> JObject {
    new_map_with(kind, JObject::null(), HashCore::new(kind != MapKind::Hash))
}

/// `new HashMap<>(initialCapacity)`。
pub fn new_map_with_capacity(kind: MapKind, cap: i32) -> JResult<JObject> {
    Ok(new_map_with(kind, JObject::null(), HashCore::with_capacity(kind != MapKind::Hash, cap)?))
}

/// `new TreeMap<>(comparator)`。
pub fn new_tree_map(comparator: JObject) -> JObject {
    new_map_with(MapKind::Tree, comparator, HashCore::new(false))
}

fn new_map_with(kind: MapKind, comparator: JObject, hash: HashCore) -> JObject {
    let imp = match kind {
        MapKind::Tree => MapImpl::Tree(RefCell::new(TreeCore::new(comparator))),
        _ => MapImpl::Hash(RefCell::new(hash)),
    };
    alloc(|base| JMap { base, kind, imp })
}

/// `new HashMap<>(map)` など。
pub fn new_map_from(kind: MapKind, source: &JObject) -> JResult<JObject> {
    let real = if kind == MapKind::Immutable { MapKind::LinkedHash } else { kind };
    let m = new_map_with(real, JObject::null(), HashCore::new(real != MapKind::Hash));
    let entries = map_entries(source)?;
    if let Some(map) = m.downcast_ref::<JMap>() {
        if let MapImpl::Hash(c) = &map.imp {
            c.borrow_mut().presize(entries.len());
        }
        for (k, v) in entries {
            map.insert(k, v)?;
        }
    }
    if kind == MapKind::Immutable {
        return Ok(freeze_map(m));
    }
    Ok(m)
}

fn freeze_map(m: JObject) -> JObject {
    let map = m.downcast_rc::<JMap>().expect("internal error: not a map");
    let imp = match &map.imp {
        MapImpl::Hash(c) => MapImpl::Hash(RefCell::new(std::mem::replace(&mut *c.borrow_mut(), HashCore::new(true)))),
        MapImpl::Tree(_) => unreachable!(),
    };
    alloc(|base| JMap { base, kind: MapKind::Immutable, imp })
}

/// `Map.of(k1, v1, ...)`。
pub fn map_of(pairs: Vec<(JObject, JObject)>) -> JResult<JObject> {
    let m = new_map_with(MapKind::LinkedHash, JObject::null(), HashCore::new(true));
    if let Some(map) = m.downcast_ref::<JMap>() {
        for (k, v) in pairs {
            if k.is_null() || v.is_null() {
                return npe();
            }
            if !map.insert(k.clone(), v)?.is_null() {
                return throw("java.lang.IllegalArgumentException", Some(&format!("duplicate key: {}", to_java_string(&k)?)));
            }
        }
    }
    Ok(freeze_map(m))
}

fn map_entries(m: &JObject) -> JResult<Vec<(JObject, JObject)>> {
    match m.downcast_ref::<JMap>() {
        Some(map) => Ok(map.entries().iter().map(|e| (e.key.clone(), e.value())).collect()),
        None => {
            let mut out = Vec::new();
            for e in to_vec(&m.invoke("entrySet", &[])?)? {
                out.push((e.get_key()?, e.get_value()?));
            }
            Ok(out)
        }
    }
}

// ================================================================== PriorityQueue

pub struct JPriorityQueue {
    base: ObjectBase,
    heap: RefCell<Vec<JObject>>,
    comparator: JObject,
}

impl JPriorityQueue {
    // 比較関数（ユーザーのコード）は、ヒープを借用していない状態で呼ぶ。
    fn sift_up(&self, mut k: usize, x: JObject) -> JResult<()> {
        while k > 0 {
            let parent = (k - 1) >> 1;
            let e = self.heap.borrow()[parent].clone();
            if compare_with(&self.comparator, &x, &e)? >= 0 {
                break;
            }
            self.heap.borrow_mut()[k] = e;
            k = parent;
        }
        self.heap.borrow_mut()[k] = x;
        Ok(())
    }

    fn sift_down(&self, mut k: usize, x: JObject, n: usize) -> JResult<()> {
        let half = n >> 1;
        while k < half {
            let mut child = 2 * k + 1;
            let mut c = self.heap.borrow()[child].clone();
            let right = child + 1;
            if right < n {
                let r = self.heap.borrow()[right].clone();
                if compare_with(&self.comparator, &c, &r)? > 0 {
                    child = right;
                    c = r;
                }
            }
            if compare_with(&self.comparator, &x, &c)? <= 0 {
                break;
            }
            self.heap.borrow_mut()[k] = c;
            k = child;
        }
        self.heap.borrow_mut()[k] = x;
        Ok(())
    }

    fn offer(&self, x: JObject) -> JResult<bool> {
        if x.is_null() {
            return npe();
        }
        let i = self.heap.borrow().len();
        if i == 0 {
            // 最初の要素も比較可能かを確かめる（Java と同様、Comparable でなければ ClassCastException）。
            compare_with(&self.comparator, &x, &x)?;
        }
        self.heap.borrow_mut().push(x.clone());
        self.sift_up(i, x)?;
        Ok(true)
    }

    fn poll(&self) -> JResult<JObject> {
        let (result, last, n) = {
            let mut q = self.heap.borrow_mut();
            let Some(last) = q.pop() else { return Ok(JObject::null()) };
            if q.is_empty() {
                return Ok(last);
            }
            (q[0].clone(), last, q.len())
        };
        self.sift_down(0, last, n)?;
        Ok(result)
    }

    fn remove_at(&self, i: usize) -> JResult<()> {
        let (moved, n) = {
            let mut q = self.heap.borrow_mut();
            let s = q.len() - 1;
            let last = q.pop().unwrap_or_default();
            if s == i {
                return Ok(());
            }
            (last, s)
        };
        self.sift_down(i, moved.clone(), n)?;
        let same = self.heap.borrow()[i].same(&moved);
        if same {
            self.sift_up(i, moved)?;
        }
        Ok(())
    }
}

impl Object for JPriorityQueue {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.PriorityQueue"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.PriorityQueue" | "java.util.Queue" | "java.util.Collection" | "java.lang.Iterable" | "java.util.AbstractQueue")
    }
    fn to_jstring(&self) -> JResult<JString> {
        let items = self.heap.borrow().clone();
        Ok(join(show_all(&self.base().this(), &items)?, "[", "]"))
    }
}

/// `new PriorityQueue<>()` / `new PriorityQueue<>(comparator)`。
pub fn new_priority_queue(comparator: JObject) -> JObject {
    alloc(|base| JPriorityQueue { base, heap: RefCell::new(Vec::new()), comparator })
}

/// `new PriorityQueue<>(initialCapacity)`（1 未満なら IllegalArgumentException）。
pub fn new_priority_queue_with_capacity(cap: i32) -> JResult<JObject> {
    if cap < 1 {
        return throw("java.lang.IllegalArgumentException", None);
    }
    Ok(new_priority_queue(JObject::null()))
}

/// `new PriorityQueue<>(collection)`（heapify）。
pub fn new_priority_queue_from(source: &JObject) -> JResult<JObject> {
    let items = to_vec(source)?;
    let n = items.len();
    if items.iter().any(JObject::is_null) {
        return npe();
    }
    let pq = alloc(|base| JPriorityQueue { base, heap: RefCell::new(items), comparator: JObject::null() });
    if let Some(q) = pq.downcast_ref::<JPriorityQueue>() {
        if n == 1 {
            let x = q.heap.borrow()[0].clone();
            compare_with(&q.comparator, &x, &x)?;
        }
        if n > 1 {
            for i in (0..(n >> 1)).rev() {
                let x = q.heap.borrow()[i].clone();
                q.sift_down(i, x, n)?;
            }
        }
    }
    Ok(pq)
}

// ================================================================== Iterator

enum IterSource {
    /// リスト（削除は位置で行う）。
    List(JObject),
    /// 集合・マップのビュー（削除は要素 / キーで行う）。
    Set(JObject),
    MapKeys(JObject),
    MapEntries(JObject),
    PriorityQueue(JObject),
    None,
}

pub struct JIterator {
    base: ObjectBase,
    items: Vec<JObject>,
    pos: Cell<usize>,
    removed: Cell<usize>,
    can_remove: Cell<bool>,
    source: IterSource,
}

impl Object for JIterator {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.Iterator"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.Iterator" | "java.util.ListIterator")
    }
}

impl JIterator {
    fn remove(&self) -> JResult<()> {
        if !self.can_remove.get() {
            return throw("java.lang.IllegalStateException", None);
        }
        self.can_remove.set(false);
        let last = self.items[self.pos.get() - 1].clone();
        match &self.source {
            IterSource::List(l) => {
                let idx = self.pos.get() - 1 - self.removed.get();
                l.remove_at(idx as i32)?;
                self.removed.set(self.removed.get() + 1);
            }
            IterSource::Set(s) => {
                s.remove(last)?;
            }
            IterSource::MapKeys(m) => {
                m.map_remove(last)?;
            }
            IterSource::MapEntries(m) => {
                m.map_remove(last.get_key()?)?;
            }
            IterSource::PriorityQueue(q) => {
                q.remove(last)?;
            }
            IterSource::None => return unsupported(),
        }
        Ok(())
    }
}

fn new_iterator(items: Vec<JObject>, source: IterSource) -> JObject {
    alloc(|base| JIterator { base, items, pos: Cell::new(0), removed: Cell::new(0), can_remove: Cell::new(false), source })
}

/// 要素の一覧（Iterable なら何でもよい。ユーザーのクラスは iterator() を呼ぶ）。null なら NullPointerException。
pub fn to_vec(c: &JObject) -> JResult<Vec<JObject>> {
    if let Some(l) = c.downcast_ref::<JList>() {
        return Ok(l.snapshot());
    }
    if let Some(s) = c.downcast_ref::<JSet>() {
        return Ok(s.elements());
    }
    if let Some(q) = c.downcast_ref::<JPriorityQueue>() {
        return Ok(q.heap.borrow().clone());
    }
    if c.is::<JMap>() {
        return throw("java.lang.ClassCastException", Some("a Map is not a Collection"));
    }
    let it = c.iterator()?;
    let mut out = Vec::new();
    while it.has_next()? {
        out.push(it.next()?);
    }
    Ok(out)
}

fn contains_any(c: &JObject, x: &JObject) -> JResult<bool> {
    if let Some(s) = c.downcast_ref::<JSet>() {
        return s.contains(x);
    }
    if let Some(l) = c.downcast_ref::<JList>() {
        return match l.kind {
            ListKind::KeySet => l.backing.contains_key(x.clone()),
            _ => Ok(index_of_in(&l.snapshot(), x)?.is_some()),
        };
    }
    Ok(index_of_in(&to_vec(c)?, x)?.is_some())
}

// ================================================================== JObject のメソッド

/// コレクション・マップ・イテレータ・Map.Entry の操作。`use jrt::prelude::*;` で使える。
/// レシーバが null なら NullPointerException、ユーザーのコード（equals・比較関数など）の例外はそのまま伝える。
pub trait Collections {
    // ---- Collection
    fn size(&self) -> JResult<i32>;
    fn is_empty(&self) -> JResult<bool>;
    fn contains(&self, x: impl Into<JObject>) -> JResult<bool>;
    fn add(&self, x: impl Into<JObject>) -> JResult<bool>;
    fn remove(&self, x: impl Into<JObject>) -> JResult<bool>;
    fn clear(&self) -> JResult<()>;
    fn add_all(&self, c: &JObject) -> JResult<bool>;
    fn remove_all(&self, c: &JObject) -> JResult<bool>;
    fn retain_all(&self, c: &JObject) -> JResult<bool>;
    fn contains_all(&self, c: &JObject) -> JResult<bool>;
    fn iterator(&self) -> JResult<JObject>;
    fn for_each(&self, action: &JObject) -> JResult<()>;
    fn remove_if(&self, filter: &JObject) -> JResult<bool>;
    // ---- List
    fn get(&self, index: i32) -> JResult<JObject>;
    fn set(&self, index: i32, x: impl Into<JObject>) -> JResult<JObject>;
    fn add_at(&self, index: i32, x: impl Into<JObject>) -> JResult<()>;
    fn remove_at(&self, index: i32) -> JResult<JObject>;
    fn index_of(&self, x: impl Into<JObject>) -> JResult<i32>;
    fn last_index_of(&self, x: impl Into<JObject>) -> JResult<i32>;
    fn sort(&self, comparator: &JObject) -> JResult<()>;
    fn sub_list(&self, from: i32, to: i32) -> JResult<JObject>;
    fn replace_all(&self, op: &JObject) -> JResult<()>;
    fn get_first(&self) -> JResult<JObject>;
    fn get_last(&self) -> JResult<JObject>;
    fn remove_first(&self) -> JResult<JObject>;
    fn remove_last(&self) -> JResult<JObject>;
    // ---- Queue / Deque / Stack
    fn offer(&self, x: impl Into<JObject>) -> JResult<bool>;
    fn poll(&self) -> JResult<JObject>;
    fn peek(&self) -> JResult<JObject>;
    fn element(&self) -> JResult<JObject>;
    fn remove_head(&self) -> JResult<JObject>;
    fn push(&self, x: impl Into<JObject>) -> JResult<JObject>;
    fn pop(&self) -> JResult<JObject>;
    fn add_first(&self, x: impl Into<JObject>) -> JResult<()>;
    fn add_last(&self, x: impl Into<JObject>) -> JResult<()>;
    fn offer_first(&self, x: impl Into<JObject>) -> JResult<bool>;
    fn offer_last(&self, x: impl Into<JObject>) -> JResult<bool>;
    fn poll_first(&self) -> JResult<JObject>;
    fn poll_last(&self) -> JResult<JObject>;
    fn peek_first(&self) -> JResult<JObject>;
    fn peek_last(&self) -> JResult<JObject>;
    // ---- SortedSet / NavigableSet
    fn first(&self) -> JResult<JObject>;
    fn last(&self) -> JResult<JObject>;
    fn floor(&self, x: impl Into<JObject>) -> JResult<JObject>;
    fn ceiling(&self, x: impl Into<JObject>) -> JResult<JObject>;
    fn higher(&self, x: impl Into<JObject>) -> JResult<JObject>;
    fn lower(&self, x: impl Into<JObject>) -> JResult<JObject>;
    fn head_set(&self, to: impl Into<JObject>, inclusive: bool) -> JResult<JObject>;
    fn tail_set(&self, from: impl Into<JObject>, inclusive: bool) -> JResult<JObject>;
    // ---- Map
    fn map_get(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn put(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JResult<JObject>;
    fn contains_key(&self, key: impl Into<JObject>) -> JResult<bool>;
    fn contains_value(&self, value: impl Into<JObject>) -> JResult<bool>;
    fn map_remove(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn get_or_default(&self, key: impl Into<JObject>, default: impl Into<JObject>) -> JResult<JObject>;
    fn put_if_absent(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JResult<JObject>;
    fn put_all(&self, m: &JObject) -> JResult<()>;
    fn key_set(&self) -> JResult<JObject>;
    fn values(&self) -> JResult<JObject>;
    fn entry_set(&self) -> JResult<JObject>;
    fn compute_if_absent(&self, key: impl Into<JObject>, f: &JObject) -> JResult<JObject>;
    fn compute_if_present(&self, key: impl Into<JObject>, f: &JObject) -> JResult<JObject>;
    fn compute(&self, key: impl Into<JObject>, f: &JObject) -> JResult<JObject>;
    fn merge(&self, key: impl Into<JObject>, value: impl Into<JObject>, f: &JObject) -> JResult<JObject>;
    fn map_for_each(&self, action: &JObject) -> JResult<()>;
    fn map_replace(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JResult<JObject>;
    fn first_key(&self) -> JResult<JObject>;
    fn last_key(&self) -> JResult<JObject>;
    fn floor_key(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn ceiling_key(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn higher_key(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn lower_key(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn first_entry(&self) -> JResult<JObject>;
    fn last_entry(&self) -> JResult<JObject>;
    fn poll_first_entry(&self) -> JResult<JObject>;
    fn poll_last_entry(&self) -> JResult<JObject>;
    fn floor_entry(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn ceiling_entry(&self, key: impl Into<JObject>) -> JResult<JObject>;
    fn head_map(&self, to: impl Into<JObject>, inclusive: bool) -> JResult<JObject>;
    fn tail_map(&self, from: impl Into<JObject>, inclusive: bool) -> JResult<JObject>;
    // ---- Map.Entry
    fn get_key(&self) -> JResult<JObject>;
    fn get_value(&self) -> JResult<JObject>;
    fn set_value(&self, value: impl Into<JObject>) -> JResult<JObject>;
    // ---- Iterator
    fn has_next(&self) -> JResult<bool>;
    fn next(&self) -> JResult<JObject>;
    fn iter_remove(&self) -> JResult<()>;
}

fn list(o: &JObject) -> Option<&JList> {
    o.downcast_ref::<JList>()
}

fn map(o: &JObject) -> JResult<&JMap> {
    match o.downcast_ref::<JMap>() {
        Some(m) => Ok(m),
        None => {
            o.obj()?;
            unsupported()
        }
    }
}

fn deque(o: &JObject) -> JResult<&JList> {
    match list(o) {
        Some(l) => Ok(l),
        None => {
            o.obj()?;
            unsupported()
        }
    }
}

fn set(o: &JObject) -> JResult<&JSet> {
    match o.downcast_ref::<JSet>() {
        Some(s) => Ok(s),
        None => {
            o.obj()?;
            unsupported()
        }
    }
}

fn check_index(index: i32, len: usize) -> JResult<usize> {
    if index < 0 || index as usize >= len {
        return index_oob(index, len);
    }
    Ok(index as usize)
}

/// ユーザーのクラスが実装したコレクションのメソッドを呼ぶ。
fn call(o: &JObject, method: &str, args: &[JObject]) -> JResult<JObject> {
    o.invoke(method, args)
}

impl Collections for JObject {
    fn size(&self) -> JResult<i32> {
        if let Some(l) = list(self) {
            return Ok(l.items.borrow().len() as i32);
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return Ok(s.len() as i32);
        }
        if let Some(m) = self.downcast_ref::<JMap>() {
            return Ok(m.len() as i32);
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return Ok(q.heap.borrow().len() as i32);
        }
        call(self, "size", &[])?.unbox_i32()
    }

    fn is_empty(&self) -> JResult<bool> {
        if self.is::<JList>() || self.is::<JSet>() || self.is::<JMap>() || self.is::<JPriorityQueue>() {
            return Ok(self.size()? == 0);
        }
        match self.try_invoke("isEmpty", &[])? {
            Some(r) => r.unbox_bool(),
            None => Ok(self.size()? == 0),
        }
    }

    fn contains(&self, x: impl Into<JObject>) -> JResult<bool> {
        let x = x.into();
        if self.is::<JPriorityQueue>() {
            return Ok(index_of_in(&to_vec(self)?, &x)?.is_some());
        }
        if list(self).is_some() || self.is::<JSet>() {
            return contains_any(self, &x);
        }
        call(self, "contains", &[x])?.unbox_bool()
    }

    fn add(&self, x: impl Into<JObject>) -> JResult<bool> {
        let x = x.into();
        if let Some(l) = list(self) {
            l.check_modifiable()?;
            if l.kind == ListKind::ArrayDeque && x.is_null() {
                return npe();
            }
            l.items.borrow_mut().push_back(x);
            return Ok(true);
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return s.add(x);
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return q.offer(x);
        }
        call(self, "add", &[x])?.unbox_bool()
    }

    fn remove(&self, x: impl Into<JObject>) -> JResult<bool> {
        let x = x.into();
        if let Some(l) = list(self) {
            match l.kind {
                ListKind::KeySet => {
                    if !l.backing.contains_key(x.clone())? {
                        return Ok(false);
                    }
                    l.backing.map_remove(x)?;
                    return Ok(true);
                }
                ListKind::EntrySet | ListKind::Values | ListKind::Immutable | ListKind::FixedSize => return unsupported(),
                _ => {}
            }
            return match index_of_in(&l.snapshot(), &x)? {
                Some(i) => {
                    l.items.borrow_mut().remove(i);
                    Ok(true)
                }
                None => Ok(false),
            };
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return s.remove(&x);
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            let items = q.heap.borrow().clone();
            return match index_of_in(&items, &x)? {
                Some(i) => {
                    q.remove_at(i)?;
                    Ok(true)
                }
                None => Ok(false),
            };
        }
        call(self, "remove", &[x])?.unbox_bool()
    }

    fn clear(&self) -> JResult<()> {
        if let Some(l) = list(self) {
            l.check_modifiable()?;
            l.items.borrow_mut().clear();
        } else if let Some(s) = self.downcast_ref::<JSet>() {
            s.clear()?;
        } else if let Some(m) = self.downcast_ref::<JMap>() {
            m.check_modifiable()?;
            match &m.imp {
                MapImpl::Hash(c) => c.borrow_mut().clear(),
                MapImpl::Tree(c) => c.borrow_mut().entries.clear(),
            }
        } else if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            q.heap.borrow_mut().clear();
        } else {
            call(self, "clear", &[])?;
        }
        Ok(())
    }

    fn add_all(&self, c: &JObject) -> JResult<bool> {
        self.obj()?;
        let items = to_vec(c)?;
        let mut changed = false;
        for x in items {
            changed |= self.add(x)?;
        }
        Ok(changed)
    }

    fn remove_all(&self, c: &JObject) -> JResult<bool> {
        c.obj()?;
        let targets = c.clone();
        self.remove_if(&crate::lambda::lambda(&["java.util.function.Predicate"], move |a| {
            Ok(crate::lang::boxed::box_bool(contains_any(&targets, &a[0])?))
        }))
    }

    fn retain_all(&self, c: &JObject) -> JResult<bool> {
        c.obj()?;
        let targets = c.clone();
        self.remove_if(&crate::lambda::lambda(&["java.util.function.Predicate"], move |a| {
            Ok(crate::lang::boxed::box_bool(!contains_any(&targets, &a[0])?))
        }))
    }

    fn contains_all(&self, c: &JObject) -> JResult<bool> {
        self.obj()?;
        for x in to_vec(c)? {
            if !self.contains(x)? {
                return Ok(false);
            }
        }
        Ok(true)
    }

    fn iterator(&self) -> JResult<JObject> {
        if let Some(l) = list(self) {
            let source = match l.kind {
                ListKind::KeySet => IterSource::MapKeys(l.backing.clone()),
                ListKind::EntrySet => IterSource::MapEntries(l.backing.clone()),
                ListKind::Values | ListKind::Immutable | ListKind::FixedSize => IterSource::None,
                _ => IterSource::List(self.clone()),
            };
            return Ok(new_iterator(l.snapshot(), source));
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return Ok(new_iterator(s.elements(), IterSource::Set(self.clone())));
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return Ok(new_iterator(q.heap.borrow().clone(), IterSource::PriorityQueue(self.clone())));
        }
        call(self, "iterator", &[])
    }

    fn for_each(&self, action: &JObject) -> JResult<()> {
        action.obj()?;
        for x in to_vec(self)? {
            action.invoke("accept", &[x])?;
        }
        Ok(())
    }

    fn remove_if(&self, filter: &JObject) -> JResult<bool> {
        filter.obj()?;
        let items = to_vec(self)?;
        let mut changed = false;
        if let Some(l) = list(self) {
            l.check_modifiable()?;
            let mut keep = VecDeque::new();
            for x in items {
                if filter.invoke("test", &[x.clone()])?.unbox_bool()? {
                    changed = true;
                } else {
                    keep.push_back(x);
                }
            }
            *l.items.borrow_mut() = keep;
            return Ok(changed);
        }
        for x in items {
            if filter.invoke("test", &[x.clone()])?.unbox_bool()? {
                self.remove(x)?;
                changed = true;
            }
        }
        Ok(changed)
    }

    fn get(&self, index: i32) -> JResult<JObject> {
        match list(self) {
            Some(l) => {
                let items = l.items.borrow();
                let i = check_index(index, items.len())?;
                Ok(items[i].clone())
            }
            None => call(self, "get", &[crate::lang::boxed::box_i32(index)]),
        }
    }

    fn set(&self, index: i32, x: impl Into<JObject>) -> JResult<JObject> {
        let x = x.into();
        let l = deque(self)?;
        if l.kind == ListKind::Immutable {
            return unsupported();
        }
        let mut items = l.items.borrow_mut();
        let i = check_index(index, items.len())?;
        Ok(std::mem::replace(&mut items[i], x))
    }

    fn add_at(&self, index: i32, x: impl Into<JObject>) -> JResult<()> {
        let x = x.into();
        let l = deque(self)?;
        l.check_modifiable()?;
        let mut items = l.items.borrow_mut();
        if index < 0 || index as usize > items.len() {
            return index_oob(index, items.len());
        }
        items.insert(index as usize, x);
        Ok(())
    }

    fn remove_at(&self, index: i32) -> JResult<JObject> {
        let l = deque(self)?;
        l.check_modifiable()?;
        let mut items = l.items.borrow_mut();
        let i = check_index(index, items.len())?;
        Ok(items.remove(i).unwrap_or_default())
    }

    fn index_of(&self, x: impl Into<JObject>) -> JResult<i32> {
        let x = x.into();
        Ok(index_of_in(&deque(self)?.snapshot(), &x)?.map_or(-1, |i| i as i32))
    }

    fn last_index_of(&self, x: impl Into<JObject>) -> JResult<i32> {
        let x = x.into();
        let items = deque(self)?.snapshot();
        for (i, e) in items.iter().enumerate().rev() {
            if equals_nullable(&x, e)? {
                return Ok(i as i32);
            }
        }
        Ok(-1)
    }

    fn sort(&self, comparator: &JObject) -> JResult<()> {
        let l = deque(self)?;
        if l.kind == ListKind::Immutable {
            return unsupported();
        }
        let mut v = l.snapshot();
        try_sort_by(&mut v, |a, b| Ok(compare_with(comparator, a, b)?.cmp(&0)))?;
        *l.items.borrow_mut() = v.into();
        Ok(())
    }

    fn sub_list(&self, from: i32, to: i32) -> JResult<JObject> {
        let v = deque(self)?.snapshot();
        if from < 0 || to as usize > v.len() || from > to {
            return throw("java.lang.IndexOutOfBoundsException", Some(&format!("fromIndex: {from}, toIndex: {to}, size: {}", v.len())));
        }
        list_from_vec(ListKind::ArrayList, v[from as usize..to as usize].to_vec())
    }

    fn replace_all(&self, op: &JObject) -> JResult<()> {
        let l = deque(self)?;
        op.obj()?;
        let mut v = VecDeque::new();
        for x in l.snapshot() {
            v.push_back(op.invoke("apply", &[x])?);
        }
        *l.items.borrow_mut() = v;
        Ok(())
    }

    fn get_first(&self) -> JResult<JObject> {
        if self.is::<JSet>() {
            return self.first();
        }
        let x = deque(self)?.items.borrow().front().cloned();
        x.map_or_else(no_such_element, Ok)
    }

    fn get_last(&self) -> JResult<JObject> {
        if self.is::<JSet>() {
            return self.last();
        }
        let x = deque(self)?.items.borrow().back().cloned();
        x.map_or_else(no_such_element, Ok)
    }

    fn remove_first(&self) -> JResult<JObject> {
        if self.is::<JSet>() {
            let x = self.first()?;
            self.remove(x.clone())?;
            return Ok(x);
        }
        let l = deque(self)?;
        l.check_modifiable()?;
        let x = l.items.borrow_mut().pop_front();
        x.map_or_else(no_such_element, Ok)
    }

    fn remove_last(&self) -> JResult<JObject> {
        if self.is::<JSet>() {
            let x = self.last()?;
            self.remove(x.clone())?;
            return Ok(x);
        }
        let l = deque(self)?;
        l.check_modifiable()?;
        let x = l.items.borrow_mut().pop_back();
        x.map_or_else(no_such_element, Ok)
    }

    fn offer(&self, x: impl Into<JObject>) -> JResult<bool> {
        self.add(x)
    }

    fn poll(&self) -> JResult<JObject> {
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return q.poll();
        }
        self.poll_first()
    }

    fn peek(&self) -> JResult<JObject> {
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return Ok(q.heap.borrow().first().cloned().unwrap_or_default());
        }
        let l = deque(self)?;
        if l.kind == ListKind::Stack {
            if l.items.borrow().is_empty() {
                return throw("java.util.EmptyStackException", None);
            }
            return self.peek_last();
        }
        self.peek_first()
    }

    fn element(&self) -> JResult<JObject> {
        let x = self.peek()?;
        if x.is_null() && self.is_empty()? {
            return no_such_element();
        }
        Ok(x)
    }

    fn remove_head(&self) -> JResult<JObject> {
        if self.is::<JPriorityQueue>() {
            if self.is_empty()? {
                return no_such_element();
            }
            return self.poll();
        }
        self.remove_first()
    }

    fn push(&self, x: impl Into<JObject>) -> JResult<JObject> {
        let x = x.into();
        let l = deque(self)?;
        if l.kind == ListKind::Stack {
            l.items.borrow_mut().push_back(x.clone());
        } else {
            self.add_first(x.clone())?;
        }
        Ok(x)
    }

    fn pop(&self) -> JResult<JObject> {
        let l = deque(self)?;
        if l.kind == ListKind::Stack {
            let x = l.items.borrow_mut().pop_back();
            return x.map_or_else(|| throw("java.util.EmptyStackException", None), Ok);
        }
        self.remove_first()
    }

    fn add_first(&self, x: impl Into<JObject>) -> JResult<()> {
        let x = x.into();
        let l = deque(self)?;
        l.check_modifiable()?;
        if l.kind == ListKind::ArrayDeque && x.is_null() {
            return npe();
        }
        l.items.borrow_mut().push_front(x);
        Ok(())
    }

    fn add_last(&self, x: impl Into<JObject>) -> JResult<()> {
        let x = x.into();
        let l = deque(self)?;
        l.check_modifiable()?;
        if l.kind == ListKind::ArrayDeque && x.is_null() {
            return npe();
        }
        l.items.borrow_mut().push_back(x);
        Ok(())
    }

    fn offer_first(&self, x: impl Into<JObject>) -> JResult<bool> {
        self.add_first(x)?;
        Ok(true)
    }

    fn offer_last(&self, x: impl Into<JObject>) -> JResult<bool> {
        self.add_last(x)?;
        Ok(true)
    }

    fn poll_first(&self) -> JResult<JObject> {
        if let Some(s) = self.downcast_ref::<JSet>() {
            let first = s.tree()?.borrow().entries.first().map(|e| e.key.clone());
            if let Some(x) = &first {
                s.remove(x)?;
            }
            return Ok(first.unwrap_or_default());
        }
        let l = deque(self)?;
        let x = l.items.borrow_mut().pop_front();
        Ok(x.unwrap_or_default())
    }

    fn poll_last(&self) -> JResult<JObject> {
        if let Some(s) = self.downcast_ref::<JSet>() {
            let last = s.tree()?.borrow().entries.last().map(|e| e.key.clone());
            if let Some(x) = &last {
                s.remove(x)?;
            }
            return Ok(last.unwrap_or_default());
        }
        let l = deque(self)?;
        let x = l.items.borrow_mut().pop_back();
        Ok(x.unwrap_or_default())
    }

    fn peek_first(&self) -> JResult<JObject> {
        Ok(deque(self)?.items.borrow().front().cloned().unwrap_or_default())
    }

    fn peek_last(&self) -> JResult<JObject> {
        Ok(deque(self)?.items.borrow().back().cloned().unwrap_or_default())
    }

    fn first(&self) -> JResult<JObject> {
        let x = set(self)?.tree()?.borrow().entries.first().map(|e| e.key.clone());
        x.map_or_else(no_such_element, Ok)
    }

    fn last(&self) -> JResult<JObject> {
        let x = set(self)?.tree()?.borrow().entries.last().map(|e| e.key.clone());
        x.map_or_else(no_such_element, Ok)
    }

    fn floor(&self, x: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(set(self)?.tree()?, &x.into(), Nav::Floor)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn ceiling(&self, x: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(set(self)?.tree()?, &x.into(), Nav::Ceiling)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn higher(&self, x: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(set(self)?.tree()?, &x.into(), Nav::Higher)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn lower(&self, x: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(set(self)?.tree()?, &x.into(), Nav::Lower)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn head_set(&self, to: impl Into<JObject>, inclusive: bool) -> JResult<JObject> {
        let to = to.into();
        let (cmp, entries) = {
            let t = set(self)?.tree()?.borrow();
            (t.comparator.clone(), t.entries.clone())
        };
        let out = new_tree_set(cmp.clone());
        for e in entries {
            let c = compare_with(&cmp, &e.key, &to)?;
            if c < 0 || (inclusive && c == 0) {
                out.add(e.key.clone())?;
            }
        }
        Ok(out)
    }

    fn tail_set(&self, from: impl Into<JObject>, inclusive: bool) -> JResult<JObject> {
        let from = from.into();
        let (cmp, entries) = {
            let t = set(self)?.tree()?.borrow();
            (t.comparator.clone(), t.entries.clone())
        };
        let out = new_tree_set(cmp.clone());
        for e in entries {
            let c = compare_with(&cmp, &e.key, &from)?;
            if c > 0 || (inclusive && c == 0) {
                out.add(e.key.clone())?;
            }
        }
        Ok(out)
    }

    fn map_get(&self, key: impl Into<JObject>) -> JResult<JObject> {
        let key = key.into();
        match self.downcast_ref::<JMap>() {
            Some(m) => Ok(m.find(&key)?.map(|e| e.value()).unwrap_or_default()),
            None => call(self, "get", &[key]),
        }
    }

    fn put(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JResult<JObject> {
        map(self)?.put(key.into(), value.into())
    }

    fn contains_key(&self, key: impl Into<JObject>) -> JResult<bool> {
        let key = key.into();
        match self.downcast_ref::<JMap>() {
            Some(m) => Ok(m.find(&key)?.is_some()),
            None => call(self, "containsKey", &[key])?.unbox_bool(),
        }
    }

    fn contains_value(&self, value: impl Into<JObject>) -> JResult<bool> {
        let v = value.into();
        for e in map(self)?.entries() {
            if equals_nullable(&v, &e.value())? {
                return Ok(true);
            }
        }
        Ok(false)
    }

    fn map_remove(&self, key: impl Into<JObject>) -> JResult<JObject> {
        Ok(map(self)?.remove(&key.into())?.map(|e| e.value()).unwrap_or_default())
    }

    fn get_or_default(&self, key: impl Into<JObject>, default: impl Into<JObject>) -> JResult<JObject> {
        Ok(match map(self)?.find(&key.into())? {
            Some(e) => e.value(),
            None => default.into(),
        })
    }

    fn put_if_absent(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JResult<JObject> {
        let key = key.into();
        let m = map(self)?;
        match m.find(&key)? {
            Some(e) if !e.value().is_null() => Ok(e.value()),
            _ => {
                m.put(key, value.into())?;
                Ok(JObject::null())
            }
        }
    }

    fn put_all(&self, other: &JObject) -> JResult<()> {
        let m = map(self)?;
        m.check_modifiable()?;
        let entries = map_entries(other)?;
        if let MapImpl::Hash(c) = &m.imp {
            c.borrow_mut().presize(entries.len());
        }
        for (k, v) in entries {
            m.put(k, v)?;
        }
        Ok(())
    }

    fn key_set(&self) -> JResult<JObject> {
        let keys: VecDeque<JObject> = map(self)?.entries().iter().map(|e| e.key.clone()).collect();
        Ok(new_list_of(ListKind::KeySet, keys, self.clone()))
    }

    fn values(&self) -> JResult<JObject> {
        let vals: VecDeque<JObject> = map(self)?.entries().iter().map(|e| e.value()).collect();
        Ok(new_list_of(ListKind::Values, vals, self.clone()))
    }

    fn entry_set(&self) -> JResult<JObject> {
        let es: VecDeque<JObject> = map(self)?.entries().iter().map(|e| e.as_object()).collect();
        Ok(new_list_of(ListKind::EntrySet, es, self.clone()))
    }

    fn compute_if_absent(&self, key: impl Into<JObject>, f: &JObject) -> JResult<JObject> {
        let key = key.into();
        let m = map(self)?;
        f.obj()?;
        m.begin_compute()?;
        let existing = m.find(&key)?;
        if let Some(e) = &existing {
            if !e.value().is_null() {
                return Ok(e.value());
            }
        }
        let v = f.invoke("apply", &[key.clone()])?;
        if !v.is_null() {
            match existing {
                Some(e) => {
                    e.set_value(v.clone());
                }
                None => m.insert_computed(key, v.clone())?,
            }
        }
        Ok(v)
    }

    fn compute_if_present(&self, key: impl Into<JObject>, f: &JObject) -> JResult<JObject> {
        let key = key.into();
        let m = map(self)?;
        f.obj()?;
        match m.find(&key)? {
            Some(e) if !e.value().is_null() => {
                let v = f.invoke("apply", &[key.clone(), e.value()])?;
                if v.is_null() {
                    m.remove(&key)?;
                } else {
                    e.set_value(v.clone());
                }
                Ok(v)
            }
            _ => Ok(JObject::null()),
        }
    }

    fn compute(&self, key: impl Into<JObject>, f: &JObject) -> JResult<JObject> {
        let key = key.into();
        let m = map(self)?;
        f.obj()?;
        m.begin_compute()?;
        let existing = m.find(&key)?;
        let old = existing.as_ref().map(|e| e.value()).unwrap_or_default();
        let v = f.invoke("apply", &[key.clone(), old])?;
        match (existing, v.is_null()) {
            (Some(_), true) => {
                m.remove(&key)?;
            }
            (Some(e), false) => {
                e.set_value(v.clone());
            }
            (None, false) => m.insert_computed(key, v.clone())?,
            (None, true) => {}
        }
        Ok(v)
    }

    fn merge(&self, key: impl Into<JObject>, value: impl Into<JObject>, f: &JObject) -> JResult<JObject> {
        let (key, value) = (key.into(), value.into());
        let m = map(self)?;
        if value.is_null() || f.is_null() {
            return npe();
        }
        m.begin_compute()?;
        match m.find(&key)? {
            Some(e) if !e.value().is_null() => {
                let v = f.invoke("apply", &[e.value(), value])?;
                if v.is_null() {
                    m.remove(&key)?;
                } else {
                    e.set_value(v.clone());
                }
                Ok(v)
            }
            Some(e) => {
                e.set_value(value.clone());
                Ok(value)
            }
            None => {
                m.insert_computed(key, value.clone())?;
                Ok(value)
            }
        }
    }

    fn map_for_each(&self, action: &JObject) -> JResult<()> {
        self.obj()?;
        action.obj()?;
        for (k, v) in map_entries(self)? {
            action.invoke("accept", &[k, v])?;
        }
        Ok(())
    }

    fn map_replace(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JResult<JObject> {
        let key = key.into();
        let m = map(self)?;
        m.check_modifiable()?;
        Ok(match m.find(&key)? {
            Some(e) => e.set_value(value.into()),
            None => JObject::null(),
        })
    }

    fn first_key(&self) -> JResult<JObject> {
        let x = map(self)?.tree()?.borrow().entries.first().map(|e| e.key.clone());
        x.map_or_else(no_such_element, Ok)
    }

    fn last_key(&self) -> JResult<JObject> {
        let x = map(self)?.tree()?.borrow().entries.last().map(|e| e.key.clone());
        x.map_or_else(no_such_element, Ok)
    }

    fn floor_key(&self, key: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(map(self)?.tree()?, &key.into(), Nav::Floor)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn ceiling_key(&self, key: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(map(self)?.tree()?, &key.into(), Nav::Ceiling)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn higher_key(&self, key: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(map(self)?.tree()?, &key.into(), Nav::Higher)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn lower_key(&self, key: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(map(self)?.tree()?, &key.into(), Nav::Lower)?.map(|e| e.key.clone()).unwrap_or_default())
    }

    fn first_entry(&self) -> JResult<JObject> {
        let x = map(self)?.tree()?.borrow().entries.first().cloned();
        Ok(x.map(|e| e.as_object()).unwrap_or_default())
    }

    fn last_entry(&self) -> JResult<JObject> {
        let x = map(self)?.tree()?.borrow().entries.last().cloned();
        Ok(x.map(|e| e.as_object()).unwrap_or_default())
    }

    fn poll_first_entry(&self) -> JResult<JObject> {
        let t = map(self)?.tree()?;
        let x = if t.borrow().entries.is_empty() { None } else { Some(t.borrow_mut().entries.remove(0)) };
        Ok(x.map(|e| e.as_object()).unwrap_or_default())
    }

    fn poll_last_entry(&self) -> JResult<JObject> {
        let t = map(self)?.tree()?;
        let x = t.borrow_mut().entries.pop();
        Ok(x.map(|e| e.as_object()).unwrap_or_default())
    }

    fn floor_entry(&self, key: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(map(self)?.tree()?, &key.into(), Nav::Floor)?.map(|e| e.as_object()).unwrap_or_default())
    }

    fn ceiling_entry(&self, key: impl Into<JObject>) -> JResult<JObject> {
        Ok(navigate(map(self)?.tree()?, &key.into(), Nav::Ceiling)?.map(|e| e.as_object()).unwrap_or_default())
    }

    fn head_map(&self, to: impl Into<JObject>, inclusive: bool) -> JResult<JObject> {
        let to = to.into();
        let (cmp, entries) = {
            let t = map(self)?.tree()?.borrow();
            (t.comparator.clone(), t.entries.clone())
        };
        let out = new_tree_map(cmp.clone());
        for e in entries {
            let c = compare_with(&cmp, &e.key, &to)?;
            if c < 0 || (inclusive && c == 0) {
                out.put(e.key.clone(), e.value())?;
            }
        }
        Ok(out)
    }

    fn tail_map(&self, from: impl Into<JObject>, inclusive: bool) -> JResult<JObject> {
        let from = from.into();
        let (cmp, entries) = {
            let t = map(self)?.tree()?.borrow();
            (t.comparator.clone(), t.entries.clone())
        };
        let out = new_tree_map(cmp.clone());
        for e in entries {
            let c = compare_with(&cmp, &e.key, &from)?;
            if c > 0 || (inclusive && c == 0) {
                out.put(e.key.clone(), e.value())?;
            }
        }
        Ok(out)
    }

    fn get_key(&self) -> JResult<JObject> {
        match self.downcast_ref::<MapEntry>() {
            Some(e) => Ok(e.key()),
            None => call(self, "getKey", &[]),
        }
    }

    fn get_value(&self) -> JResult<JObject> {
        match self.downcast_ref::<MapEntry>() {
            Some(e) => Ok(e.value()),
            None => call(self, "getValue", &[]),
        }
    }

    fn set_value(&self, value: impl Into<JObject>) -> JResult<JObject> {
        match self.downcast_ref::<MapEntry>() {
            Some(e) => Ok(e.set_value(value.into())),
            None => call(self, "setValue", &[value.into()]),
        }
    }

    fn has_next(&self) -> JResult<bool> {
        match self.downcast_ref::<JIterator>() {
            Some(it) => Ok(it.pos.get() < it.items.len()),
            None => call(self, "hasNext", &[])?.unbox_bool(),
        }
    }

    fn next(&self) -> JResult<JObject> {
        match self.downcast_ref::<JIterator>() {
            Some(it) => {
                let p = it.pos.get();
                if p >= it.items.len() {
                    return no_such_element();
                }
                it.pos.set(p + 1);
                it.can_remove.set(true);
                Ok(it.items[p].clone())
            }
            None => call(self, "next", &[]),
        }
    }

    fn iter_remove(&self) -> JResult<()> {
        match self.downcast_ref::<JIterator>() {
            Some(it) => it.remove(),
            None => {
                call(self, "remove", &[])?;
                Ok(())
            }
        }
    }
}

enum Nav {
    Floor,
    Ceiling,
    Higher,
    Lower,
}

fn navigate(tree: &RefCell<TreeCore>, key: &JObject, nav: Nav) -> JResult<Option<Rc<MapEntry>>> {
    let t = tree.borrow();
    let n = t.entries.len();
    let idx: Option<usize> = match (t.search(key)?, nav) {
        (Ok(i), Nav::Floor) | (Ok(i), Nav::Ceiling) => Some(i),
        (Ok(i), Nav::Higher) => (i + 1 < n).then_some(i + 1),
        (Ok(i), Nav::Lower) => i.checked_sub(1),
        (Err(i), Nav::Floor) | (Err(i), Nav::Lower) => i.checked_sub(1),
        (Err(i), Nav::Ceiling) | (Err(i), Nav::Higher) => (i < n).then_some(i),
    };
    Ok(idx.map(|i| t.entries[i].clone()))
}

/// `Map.entry(k, v)`（null は NullPointerException）。
pub fn map_entry(key: impl Into<JObject>, value: impl Into<JObject>) -> JResult<JObject> {
    let (k, v) = (key.into(), value.into());
    if k.is_null() || v.is_null() {
        return npe();
    }
    Ok(new_entry(k, v))
}
