//! java.util のコレクション（List / Deque / Set / Map / PriorityQueue）と Iterator。
//!
//! どのコレクションも `JObject` として扱い、操作は [`Collections`] トレイトのメソッド（`list.add(x)` など）で行う。
//! 実行時の型で振り分けるので、`List` 型の変数に ArrayList と LinkedList のどちらが入っていても同じコードで動く。
//! ユーザーのクラスがコレクションのインタフェースを実装している場合は、`Object::invoke` でそのメソッドを呼ぶ。

use crate::lang::string::JString;
use crate::object::{alloc, equals_nullable, hash_nullable, JObject, Object, ObjectBase};
use crate::rt::throw;
use crate::util::core::{compare_with, new_entry, spread, table_size_for, HashCore, MapEntry, TreeCore};
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;
use std::rc::Rc;

fn unsupported() -> ! {
    throw("java.lang.UnsupportedOperationException", None)
}

fn no_such_element() -> ! {
    throw("java.util.NoSuchElementException", None)
}

fn index_oob(index: i32, len: usize) -> ! {
    throw(
        "java.lang.IndexOutOfBoundsException",
        Some(&format!("Index {index} out of bounds for length {len}")),
    )
}

fn join(items: impl Iterator<Item = String>, open: &str, close: &str) -> JString {
    let mut s = String::from(open);
    for (i, x) in items.enumerate() {
        if i > 0 {
            s.push_str(", ");
        }
        s.push_str(&x);
    }
    s.push_str(close);
    JString::from(s)
}

fn show(o: &JObject) -> String {
    let mut s = String::new();
    crate::JStringify::append_to(o, &mut s);
    s
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
    fn check_modifiable(&self) {
        if matches!(self.kind, ListKind::Immutable | ListKind::FixedSize | ListKind::KeySet | ListKind::Values | ListKind::EntrySet) {
            unsupported();
        }
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
    fn to_jstring(&self) -> JString {
        join(self.snapshot().iter().map(show), "[", "]")
    }
    fn equals(&self, other: &JObject) -> bool {
        if other.same(&self.base().this()) {
            return true;
        }
        match self.kind {
            ListKind::ArrayDeque => false,
            ListKind::KeySet | ListKind::EntrySet => set_equals(&self.base().this(), other),
            ListKind::Values => false,
            _ => match other.downcast_ref::<JList>() {
                Some(o) if o.is_list() => {
                    let (a, b) = (self.snapshot(), o.snapshot());
                    a.len() == b.len() && a.iter().zip(b.iter()).all(|(x, y)| equals_nullable(x, y))
                }
                _ => false,
            },
        }
    }
    fn hash_code(&self) -> i32 {
        match self.kind {
            ListKind::ArrayDeque | ListKind::Values => self.base().identity_hash(),
            ListKind::KeySet | ListKind::EntrySet => self.snapshot().iter().fold(0i32, |h, x| h.wrapping_add(hash_nullable(x))),
            _ => self.snapshot().iter().fold(1i32, |h, x| h.wrapping_mul(31).wrapping_add(hash_nullable(x))),
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

/// `new ArrayList<>(collection)` など。
pub fn new_list_from(kind: ListKind, source: &JObject) -> JObject {
    new_list_of(kind, to_vec(source).into(), JObject::null())
}

/// 要素の配列からリストを作る（List.of / Arrays.asList / Collections.unmodifiableList）。
pub fn list_from_vec(kind: ListKind, items: Vec<JObject>) -> JObject {
    if kind == ListKind::Immutable && items.iter().any(JObject::is_null) {
        throw("java.lang.NullPointerException", None);
    }
    new_list_of(kind, items.into(), JObject::null())
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

    fn contains(&self, x: &JObject) -> bool {
        match &self.imp {
            SetImpl::Hash(c) => c.borrow().find(x, spread(x)).is_some(),
            SetImpl::Tree(c) => c.borrow().search(x).is_ok(),
        }
    }

    fn add(&self, x: JObject) -> bool {
        if self.kind == SetKind::Immutable {
            unsupported();
        }
        match &self.imp {
            SetImpl::Hash(c) => {
                let h = spread(&x);
                if c.borrow().find(&x, h).is_some() {
                    return false;
                }
                c.borrow_mut().insert_new(MapEntry::new(h, x, JObject::null()));
                true
            }
            SetImpl::Tree(c) => {
                let pos = c.borrow().search(&x);
                match pos {
                    Ok(_) => false,
                    Err(i) => {
                        c.borrow_mut().entries.insert(i, MapEntry::new(0, x, JObject::null()));
                        true
                    }
                }
            }
        }
    }

    fn remove(&self, x: &JObject) -> bool {
        if self.kind == SetKind::Immutable {
            unsupported();
        }
        match &self.imp {
            SetImpl::Hash(c) => c.borrow_mut().remove(x, spread(x)).is_some(),
            SetImpl::Tree(c) => {
                let pos = c.borrow().search(x);
                match pos {
                    Ok(i) => {
                        c.borrow_mut().entries.remove(i);
                        true
                    }
                    Err(_) => false,
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

    fn clear(&self) {
        if self.kind == SetKind::Immutable {
            unsupported();
        }
        match &self.imp {
            SetImpl::Hash(c) => c.borrow_mut().clear(),
            SetImpl::Tree(c) => c.borrow_mut().entries.clear(),
        }
    }

    fn tree(&self) -> &RefCell<TreeCore> {
        match &self.imp {
            SetImpl::Tree(c) => c,
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
    fn to_jstring(&self) -> JString {
        join(self.elements().iter().map(show), "[", "]")
    }
    fn equals(&self, other: &JObject) -> bool {
        set_equals(&self.base().this(), other)
    }
    fn hash_code(&self) -> i32 {
        self.elements().iter().fold(0i32, |h, x| h.wrapping_add(hash_nullable(x)))
    }
}

fn set_equals(this: &JObject, other: &JObject) -> bool {
    if this.same(other) {
        return true;
    }
    if !other.instance_of("java.util.Set") {
        return false;
    }
    let (a, b) = (to_vec(this), to_vec(other));
    a.len() == b.len() && b.iter().all(|x| contains_any(this, x))
}

/// `new HashSet<>()` など。
pub fn new_set(kind: SetKind) -> JObject {
    new_set_with(kind, JObject::null(), None)
}

/// `new HashSet<>(initialCapacity)`。
pub fn new_set_with_capacity(kind: SetKind, cap: i32) -> JObject {
    new_set_with(kind, JObject::null(), Some(cap))
}

/// `new TreeSet<>(comparator)`。
pub fn new_tree_set(comparator: JObject) -> JObject {
    new_set_with(SetKind::Tree, comparator, None)
}

fn new_set_with(kind: SetKind, comparator: JObject, cap: Option<i32>) -> JObject {
    let imp = match kind {
        SetKind::Tree => SetImpl::Tree(RefCell::new(TreeCore::new(comparator))),
        _ => {
            let linked = kind != SetKind::Hash;
            SetImpl::Hash(RefCell::new(match cap {
                Some(c) => HashCore::with_capacity(linked, c),
                None => HashCore::new(linked),
            }))
        }
    };
    alloc(|base| JSet { base, kind, imp })
}

/// `new HashSet<>(collection)` など。
pub fn new_set_from(kind: SetKind, source: &JObject) -> JObject {
    let items = to_vec(source);
    let comparator = if kind == SetKind::Tree {
        source.downcast_ref::<JSet>().and_then(|s| match &s.imp {
            SetImpl::Tree(t) => Some(t.borrow().comparator.clone()),
            _ => None,
        }).unwrap_or_default()
    } else {
        JObject::null()
    };
    let s = new_set_with(if kind == SetKind::Immutable { SetKind::LinkedHash } else { kind }, comparator, None);
    if let Some(set) = s.downcast_ref::<JSet>() {
        if let SetImpl::Hash(c) = &set.imp {
            // HashSet(Collection) は max(n / .75 + 1, 16) の容量で作る。
            let cap = ((items.len() as f64 / 0.75) as usize + 1).max(16);
            c.borrow_mut().presize(table_size_for(cap) * 3 / 4);
        }
        for x in items {
            if kind == SetKind::Immutable && x.is_null() {
                throw("java.lang.NullPointerException", None);
            }
            let added = set.add(x);
            if kind == SetKind::Immutable && !added {
                throw("java.lang.IllegalArgumentException", Some("duplicate element"));
            }
        }
    }
    if kind == SetKind::Immutable {
        return freeze_set(s);
    }
    s
}

fn freeze_set(s: JObject) -> JObject {
    let set = s.downcast_rc::<JSet>().unwrap();
    let elems = set.elements();
    let frozen = new_set_with(SetKind::LinkedHash, JObject::null(), None);
    let f = frozen.downcast_ref::<JSet>().unwrap();
    for x in elems {
        f.add(x);
    }
    let imp = match &f.imp {
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

    fn find(&self, key: &JObject) -> Option<Rc<MapEntry>> {
        match &self.imp {
            MapImpl::Hash(c) => c.borrow().find(key, spread(key)),
            MapImpl::Tree(c) => {
                let c = c.borrow();
                c.search(key).ok().map(|i| c.entries[i].clone())
            }
        }
    }

    fn check_modifiable(&self) {
        if self.kind == MapKind::Immutable {
            unsupported();
        }
    }

    /// 戻り値は以前の値（なければ null）。
    fn put(&self, key: JObject, value: JObject) -> JObject {
        self.check_modifiable();
        if let Some(e) = self.find(&key) {
            return e.set_value(value);
        }
        match &self.imp {
            MapImpl::Hash(c) => {
                let h = spread(&key);
                c.borrow_mut().insert_new(MapEntry::new(h, key, value));
            }
            MapImpl::Tree(c) => {
                let pos = c.borrow().search(&key);
                if let Err(i) = pos {
                    c.borrow_mut().entries.insert(i, MapEntry::new(0, key, value));
                }
            }
        }
        JObject::null()
    }

    /// merge / compute / computeIfAbsent の冒頭処理（HashMap の表の事前拡張）。
    fn begin_compute(&self) {
        self.check_modifiable();
        if let MapImpl::Hash(c) = &self.imp {
            c.borrow_mut().pre_resize_for_compute();
        }
    }

    /// merge / compute 系で新しいキーを入れる（HashMap ではバケットの先頭）。
    fn insert_computed(&self, key: JObject, value: JObject) {
        match &self.imp {
            MapImpl::Hash(c) => {
                let h = spread(&key);
                c.borrow_mut().insert_new_head(MapEntry::new(h, key, value));
            }
            MapImpl::Tree(_) => {
                self.put(key, value);
            }
        }
    }

    fn remove(&self, key: &JObject) -> Option<Rc<MapEntry>> {
        self.check_modifiable();
        match &self.imp {
            MapImpl::Hash(c) => {
                let h = spread(key);
                c.borrow_mut().remove(key, h)
            }
            MapImpl::Tree(c) => {
                let pos = c.borrow().search(key);
                pos.ok().map(|i| c.borrow_mut().entries.remove(i))
            }
        }
    }

    fn len(&self) -> usize {
        match &self.imp {
            MapImpl::Hash(c) => c.borrow().len(),
            MapImpl::Tree(c) => c.borrow().entries.len(),
        }
    }

    fn tree(&self) -> &RefCell<TreeCore> {
        match &self.imp {
            MapImpl::Tree(c) => c,
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
    fn to_jstring(&self) -> JString {
        join(self.entries().iter().map(|e| format!("{}={}", show(&e.key), show(&e.value()))), "{", "}")
    }
    fn equals(&self, other: &JObject) -> bool {
        if other.same(&self.base().this()) {
            return true;
        }
        let Some(o) = other.downcast_ref::<JMap>() else { return false };
        if o.len() != self.len() {
            return false;
        }
        self.entries().iter().all(|e| o.find(&e.key).is_some_and(|x| equals_nullable(&x.value(), &e.value())))
    }
    fn hash_code(&self) -> i32 {
        self.entries().iter().fold(0i32, |h, e| h.wrapping_add(hash_nullable(&e.key) ^ hash_nullable(&e.value())))
    }
}

/// `new HashMap<>()` など。
pub fn new_map(kind: MapKind) -> JObject {
    new_map_with(kind, JObject::null(), None)
}

/// `new HashMap<>(initialCapacity)`。
pub fn new_map_with_capacity(kind: MapKind, cap: i32) -> JObject {
    new_map_with(kind, JObject::null(), Some(cap))
}

/// `new TreeMap<>(comparator)`。
pub fn new_tree_map(comparator: JObject) -> JObject {
    new_map_with(MapKind::Tree, comparator, None)
}

fn new_map_with(kind: MapKind, comparator: JObject, cap: Option<i32>) -> JObject {
    let imp = match kind {
        MapKind::Tree => MapImpl::Tree(RefCell::new(TreeCore::new(comparator))),
        _ => {
            let linked = kind != MapKind::Hash;
            MapImpl::Hash(RefCell::new(match cap {
                Some(c) => HashCore::with_capacity(linked, c),
                None => HashCore::new(linked),
            }))
        }
    };
    alloc(|base| JMap { base, kind, imp })
}

/// `new HashMap<>(map)` など。
pub fn new_map_from(kind: MapKind, source: &JObject) -> JObject {
    let m = new_map_with(if kind == MapKind::Immutable { MapKind::LinkedHash } else { kind }, JObject::null(), None);
    let entries = map_entries(source);
    if let Some(map) = m.downcast_ref::<JMap>() {
        if let MapImpl::Hash(c) = &map.imp {
            c.borrow_mut().presize(entries.len());
        }
        for (k, v) in entries {
            map.put(k, v);
        }
    }
    if kind == MapKind::Immutable {
        return freeze_map(m);
    }
    m
}

fn freeze_map(m: JObject) -> JObject {
    let map = m.downcast_rc::<JMap>().unwrap();
    let imp = match &map.imp {
        MapImpl::Hash(c) => MapImpl::Hash(RefCell::new(std::mem::replace(&mut *c.borrow_mut(), HashCore::new(true)))),
        MapImpl::Tree(_) => unreachable!(),
    };
    alloc(|base| JMap { base, kind: MapKind::Immutable, imp })
}

/// `Map.of(k1, v1, ...)`。
pub fn map_of(pairs: Vec<(JObject, JObject)>) -> JObject {
    let m = new_map_with(MapKind::LinkedHash, JObject::null(), None);
    let map = m.downcast_ref::<JMap>().unwrap();
    for (k, v) in pairs {
        if k.is_null() || v.is_null() {
            throw("java.lang.NullPointerException", None);
        }
        if !map.put(k.clone(), v).is_null() {
            throw("java.lang.IllegalArgumentException", Some(&format!("duplicate key: {}", show(&k))));
        }
    }
    freeze_map(m)
}

fn map_entries(m: &JObject) -> Vec<(JObject, JObject)> {
    match m.downcast_ref::<JMap>() {
        Some(map) => map.entries().iter().map(|e| (e.key.clone(), e.value())).collect(),
        None => to_vec(&m.invoke("entrySet", &[])).iter().map(|e| (e.get_key(), e.get_value())).collect(),
    }
}

// ================================================================== PriorityQueue

pub struct JPriorityQueue {
    base: ObjectBase,
    heap: RefCell<Vec<JObject>>,
    comparator: JObject,
}

impl JPriorityQueue {
    fn sift_up(&self, mut k: usize, x: JObject) {
        let mut q = self.heap.borrow_mut();
        while k > 0 {
            let parent = (k - 1) >> 1;
            let e = q[parent].clone();
            if compare_with(&self.comparator, &x, &e) >= 0 {
                break;
            }
            q[k] = e;
            k = parent;
        }
        q[k] = x;
    }

    fn sift_down(&self, mut k: usize, x: JObject, n: usize) {
        let mut q = self.heap.borrow_mut();
        let half = n >> 1;
        while k < half {
            let mut child = 2 * k + 1;
            let mut c = q[child].clone();
            let right = child + 1;
            if right < n && compare_with(&self.comparator, &c, &q[right]) > 0 {
                child = right;
                c = q[child].clone();
            }
            if compare_with(&self.comparator, &x, &c) <= 0 {
                break;
            }
            q[k] = c;
            k = child;
        }
        q[k] = x;
    }

    fn offer(&self, x: JObject) -> bool {
        if x.is_null() {
            throw("java.lang.NullPointerException", None);
        }
        let i = {
            let mut q = self.heap.borrow_mut();
            q.push(x.clone());
            q.len() - 1
        };
        if i == 0 {
            // 最初の要素も比較可能かを確かめる（Java と同様、Comparable でなければ ClassCastException）。
            compare_with(&self.comparator, &x, &x);
        }
        self.sift_up(i, x);
        true
    }

    fn poll(&self) -> JObject {
        let (result, last, n) = {
            let mut q = self.heap.borrow_mut();
            if q.is_empty() {
                return JObject::null();
            }
            let last = q.pop().unwrap();
            if q.is_empty() {
                return last;
            }
            (q[0].clone(), last, q.len())
        };
        self.sift_down(0, last, n);
        result
    }

    fn remove_at(&self, i: usize) {
        let (moved, n) = {
            let mut q = self.heap.borrow_mut();
            let s = q.len() - 1;
            if s == i {
                q.pop();
                return;
            }
            (q.pop().unwrap(), s)
        };
        self.sift_down(i, moved.clone(), n);
        let same = self.heap.borrow()[i].same(&moved);
        if same {
            self.sift_up(i, moved);
        }
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
    fn to_jstring(&self) -> JString {
        join(self.heap.borrow().clone().iter().map(show), "[", "]")
    }
}

/// `new PriorityQueue<>()` / `new PriorityQueue<>(comparator)`。
pub fn new_priority_queue(comparator: JObject) -> JObject {
    alloc(|base| JPriorityQueue { base, heap: RefCell::new(Vec::new()), comparator })
}

/// `new PriorityQueue<>(collection)`（heapify）。
pub fn new_priority_queue_from(source: &JObject) -> JObject {
    let items = to_vec(source);
    let n = items.len();
    let pq = alloc(|base| JPriorityQueue { base, heap: RefCell::new(items), comparator: JObject::null() });
    let q = pq.downcast_ref::<JPriorityQueue>().unwrap();
    if n > 1 {
        for i in (0..(n >> 1)).rev() {
            let x = q.heap.borrow()[i].clone();
            q.sift_down(i, x, n);
        }
    }
    pq
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
    fn remove(&self) {
        if !self.can_remove.get() {
            throw("java.lang.IllegalStateException", None);
        }
        self.can_remove.set(false);
        let last = self.items[self.pos.get() - 1].clone();
        match &self.source {
            IterSource::List(l) => {
                let idx = self.pos.get() - 1 - self.removed.get();
                l.remove_at(idx as i32);
                self.removed.set(self.removed.get() + 1);
            }
            IterSource::Set(s) => {
                s.remove(last);
            }
            IterSource::MapKeys(m) => {
                m.map_remove(last);
            }
            IterSource::MapEntries(m) => {
                m.map_remove(last.get_key());
            }
            IterSource::PriorityQueue(q) => {
                q.remove(last);
            }
            IterSource::None => unsupported(),
        }
    }
}

fn new_iterator(items: Vec<JObject>, source: IterSource) -> JObject {
    alloc(|base| JIterator { base, items, pos: Cell::new(0), removed: Cell::new(0), can_remove: Cell::new(false), source })
}

/// 要素の一覧（Iterable なら何でもよい。ユーザーのクラスは iterator() を呼ぶ）。
pub fn to_vec(c: &JObject) -> Vec<JObject> {
    if let Some(l) = c.downcast_ref::<JList>() {
        return l.snapshot();
    }
    if let Some(s) = c.downcast_ref::<JSet>() {
        return s.elements();
    }
    if let Some(q) = c.downcast_ref::<JPriorityQueue>() {
        return q.heap.borrow().clone();
    }
    if c.is::<JMap>() {
        throw("java.lang.ClassCastException", Some("a Map is not a Collection"));
    }
    let it = c.iterator();
    let mut out = Vec::new();
    while it.has_next() {
        out.push(it.next());
    }
    out
}

fn contains_any(c: &JObject, x: &JObject) -> bool {
    if let Some(s) = c.downcast_ref::<JSet>() {
        return s.contains(x);
    }
    if let Some(l) = c.downcast_ref::<JList>() {
        match l.kind {
            ListKind::KeySet => return l.backing.contains_key(x.clone()),
            _ => return l.snapshot().iter().any(|e| equals_nullable(x, e)),
        }
    }
    to_vec(c).iter().any(|e| equals_nullable(x, e))
}

// ================================================================== JObject のメソッド

/// コレクション・マップ・イテレータ・Map.Entry の操作。`use jrt::prelude::*;` で使える。
pub trait Collections {
    // ---- Collection
    fn size(&self) -> i32;
    fn is_empty(&self) -> bool;
    fn contains(&self, x: impl Into<JObject>) -> bool;
    fn add(&self, x: impl Into<JObject>) -> bool;
    fn remove(&self, x: impl Into<JObject>) -> bool;
    fn clear(&self);
    fn add_all(&self, c: &JObject) -> bool;
    fn remove_all(&self, c: &JObject) -> bool;
    fn retain_all(&self, c: &JObject) -> bool;
    fn contains_all(&self, c: &JObject) -> bool;
    fn iterator(&self) -> JObject;
    fn for_each(&self, action: &JObject);
    fn remove_if(&self, filter: &JObject) -> bool;
    // ---- List
    fn get(&self, index: i32) -> JObject;
    fn set(&self, index: i32, x: impl Into<JObject>) -> JObject;
    fn add_at(&self, index: i32, x: impl Into<JObject>);
    fn remove_at(&self, index: i32) -> JObject;
    fn index_of(&self, x: impl Into<JObject>) -> i32;
    fn last_index_of(&self, x: impl Into<JObject>) -> i32;
    fn sort(&self, comparator: &JObject);
    fn sub_list(&self, from: i32, to: i32) -> JObject;
    fn replace_all(&self, op: &JObject);
    fn get_first(&self) -> JObject;
    fn get_last(&self) -> JObject;
    fn remove_first(&self) -> JObject;
    fn remove_last(&self) -> JObject;
    // ---- Queue / Deque / Stack
    fn offer(&self, x: impl Into<JObject>) -> bool;
    fn poll(&self) -> JObject;
    fn peek(&self) -> JObject;
    fn element(&self) -> JObject;
    fn remove_head(&self) -> JObject;
    fn push(&self, x: impl Into<JObject>) -> JObject;
    fn pop(&self) -> JObject;
    fn add_first(&self, x: impl Into<JObject>);
    fn add_last(&self, x: impl Into<JObject>);
    fn offer_first(&self, x: impl Into<JObject>) -> bool;
    fn offer_last(&self, x: impl Into<JObject>) -> bool;
    fn poll_first(&self) -> JObject;
    fn poll_last(&self) -> JObject;
    fn peek_first(&self) -> JObject;
    fn peek_last(&self) -> JObject;
    // ---- SortedSet / NavigableSet
    fn first(&self) -> JObject;
    fn last(&self) -> JObject;
    fn floor(&self, x: impl Into<JObject>) -> JObject;
    fn ceiling(&self, x: impl Into<JObject>) -> JObject;
    fn higher(&self, x: impl Into<JObject>) -> JObject;
    fn lower(&self, x: impl Into<JObject>) -> JObject;
    fn head_set(&self, to: impl Into<JObject>, inclusive: bool) -> JObject;
    fn tail_set(&self, from: impl Into<JObject>, inclusive: bool) -> JObject;
    // ---- Map
    fn map_get(&self, key: impl Into<JObject>) -> JObject;
    fn put(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JObject;
    fn contains_key(&self, key: impl Into<JObject>) -> bool;
    fn contains_value(&self, value: impl Into<JObject>) -> bool;
    fn map_remove(&self, key: impl Into<JObject>) -> JObject;
    fn get_or_default(&self, key: impl Into<JObject>, default: impl Into<JObject>) -> JObject;
    fn put_if_absent(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JObject;
    fn put_all(&self, m: &JObject);
    fn key_set(&self) -> JObject;
    fn values(&self) -> JObject;
    fn entry_set(&self) -> JObject;
    fn compute_if_absent(&self, key: impl Into<JObject>, f: &JObject) -> JObject;
    fn compute_if_present(&self, key: impl Into<JObject>, f: &JObject) -> JObject;
    fn compute(&self, key: impl Into<JObject>, f: &JObject) -> JObject;
    fn merge(&self, key: impl Into<JObject>, value: impl Into<JObject>, f: &JObject) -> JObject;
    fn map_for_each(&self, action: &JObject);
    fn map_replace(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JObject;
    fn first_key(&self) -> JObject;
    fn last_key(&self) -> JObject;
    fn floor_key(&self, key: impl Into<JObject>) -> JObject;
    fn ceiling_key(&self, key: impl Into<JObject>) -> JObject;
    fn higher_key(&self, key: impl Into<JObject>) -> JObject;
    fn lower_key(&self, key: impl Into<JObject>) -> JObject;
    fn first_entry(&self) -> JObject;
    fn last_entry(&self) -> JObject;
    fn poll_first_entry(&self) -> JObject;
    fn poll_last_entry(&self) -> JObject;
    fn floor_entry(&self, key: impl Into<JObject>) -> JObject;
    fn ceiling_entry(&self, key: impl Into<JObject>) -> JObject;
    fn head_map(&self, to: impl Into<JObject>, inclusive: bool) -> JObject;
    fn tail_map(&self, from: impl Into<JObject>, inclusive: bool) -> JObject;
    // ---- Map.Entry
    fn get_key(&self) -> JObject;
    fn get_value(&self) -> JObject;
    fn set_value(&self, value: impl Into<JObject>) -> JObject;
    // ---- Iterator
    fn has_next(&self) -> bool;
    fn next(&self) -> JObject;
    fn iter_remove(&self);
}

fn list(o: &JObject) -> Option<&JList> {
    o.downcast_ref::<JList>()
}

fn map(o: &JObject) -> &JMap {
    match o.downcast_ref::<JMap>() {
        Some(m) => m,
        None => {
            o.obj();
            unsupported()
        }
    }
}

fn deque(o: &JObject) -> &JList {
    match list(o) {
        Some(l) => l,
        None => {
            o.obj();
            unsupported()
        }
    }
}

fn check_index(index: i32, len: usize) -> usize {
    if index < 0 || index as usize >= len {
        index_oob(index, len);
    }
    index as usize
}

/// ユーザーのクラスが実装したコレクションのメソッドを呼ぶ。
fn call(o: &JObject, method: &str, args: &[JObject]) -> JObject {
    o.invoke(method, args)
}

impl Collections for JObject {
    fn size(&self) -> i32 {
        if let Some(l) = list(self) {
            return l.items.borrow().len() as i32;
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return s.len() as i32;
        }
        if let Some(m) = self.downcast_ref::<JMap>() {
            return m.len() as i32;
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return q.heap.borrow().len() as i32;
        }
        call(self, "size", &[]).unbox_i32()
    }

    fn is_empty(&self) -> bool {
        self.size() == 0
    }

    fn contains(&self, x: impl Into<JObject>) -> bool {
        let x = x.into();
        if self.is::<JPriorityQueue>() {
            return to_vec(self).iter().any(|e| equals_nullable(&x, e));
        }
        if list(self).is_some() || self.is::<JSet>() {
            return contains_any(self, &x);
        }
        call(self, "contains", &[x]).unbox_bool()
    }

    fn add(&self, x: impl Into<JObject>) -> bool {
        let x = x.into();
        if let Some(l) = list(self) {
            l.check_modifiable();
            if l.kind == ListKind::ArrayDeque && x.is_null() {
                throw("java.lang.NullPointerException", None);
            }
            l.items.borrow_mut().push_back(x);
            return true;
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return s.add(x);
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return q.offer(x);
        }
        call(self, "add", &[x]).unbox_bool()
    }

    fn remove(&self, x: impl Into<JObject>) -> bool {
        let x = x.into();
        if let Some(l) = list(self) {
            match l.kind {
                ListKind::KeySet => {
                    if !l.backing.contains_key(x.clone()) {
                        return false;
                    }
                    l.backing.map_remove(x);
                    return true;
                }
                ListKind::EntrySet | ListKind::Values | ListKind::Immutable | ListKind::FixedSize => unsupported(),
                _ => {}
            }
            let pos = l.snapshot().iter().position(|e| equals_nullable(&x, e));
            return match pos {
                Some(i) => {
                    l.items.borrow_mut().remove(i);
                    true
                }
                None => false,
            };
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return s.remove(&x);
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            let pos = q.heap.borrow().iter().position(|e| equals_nullable(&x, e));
            return match pos {
                Some(i) => {
                    q.remove_at(i);
                    true
                }
                None => false,
            };
        }
        call(self, "remove", &[x]).unbox_bool()
    }

    fn clear(&self) {
        if let Some(l) = list(self) {
            l.check_modifiable();
            l.items.borrow_mut().clear();
        } else if let Some(s) = self.downcast_ref::<JSet>() {
            s.clear();
        } else if let Some(m) = self.downcast_ref::<JMap>() {
            m.check_modifiable();
            match &m.imp {
                MapImpl::Hash(c) => c.borrow_mut().clear(),
                MapImpl::Tree(c) => c.borrow_mut().entries.clear(),
            }
        } else if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            q.heap.borrow_mut().clear();
        } else {
            call(self, "clear", &[]);
        }
    }

    fn add_all(&self, c: &JObject) -> bool {
        let items = to_vec(c);
        let mut changed = false;
        for x in items {
            changed |= self.add(x);
        }
        changed
    }

    fn remove_all(&self, c: &JObject) -> bool {
        let targets = c.clone();
        self.remove_if(&crate::lambda::lambda(&["java.util.function.Predicate"], move |a| {
            crate::lang::boxed::box_bool(contains_any(&targets, &a[0]))
        }))
    }

    fn retain_all(&self, c: &JObject) -> bool {
        let targets = c.clone();
        self.remove_if(&crate::lambda::lambda(&["java.util.function.Predicate"], move |a| {
            crate::lang::boxed::box_bool(!contains_any(&targets, &a[0]))
        }))
    }

    fn contains_all(&self, c: &JObject) -> bool {
        to_vec(c).iter().all(|x| self.contains(x.clone()))
    }

    fn iterator(&self) -> JObject {
        if let Some(l) = list(self) {
            let source = match l.kind {
                ListKind::KeySet => IterSource::MapKeys(l.backing.clone()),
                ListKind::EntrySet => IterSource::MapEntries(l.backing.clone()),
                ListKind::Values | ListKind::Immutable | ListKind::FixedSize => IterSource::None,
                _ => IterSource::List(self.clone()),
            };
            return new_iterator(l.snapshot(), source);
        }
        if let Some(s) = self.downcast_ref::<JSet>() {
            return new_iterator(s.elements(), IterSource::Set(self.clone()));
        }
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return new_iterator(q.heap.borrow().clone(), IterSource::PriorityQueue(self.clone()));
        }
        call(self, "iterator", &[])
    }

    fn for_each(&self, action: &JObject) {
        for x in to_vec(self) {
            action.invoke("accept", &[x]);
        }
    }

    fn remove_if(&self, filter: &JObject) -> bool {
        let items = to_vec(self);
        let mut changed = false;
        if let Some(l) = list(self) {
            l.check_modifiable();
            let keep: Vec<JObject> = items
                .into_iter()
                .filter(|x| {
                    let remove = filter.invoke("test", &[x.clone()]).unbox_bool();
                    changed |= remove;
                    !remove
                })
                .collect();
            *l.items.borrow_mut() = keep.into();
            return changed;
        }
        for x in items {
            if filter.invoke("test", &[x.clone()]).unbox_bool() {
                self.remove(x);
                changed = true;
            }
        }
        changed
    }

    fn get(&self, index: i32) -> JObject {
        match list(self) {
            Some(l) => {
                let items = l.items.borrow();
                let i = check_index(index, items.len());
                items[i].clone()
            }
            None => call(self, "get", &[crate::lang::boxed::box_i32(index)]),
        }
    }

    fn set(&self, index: i32, x: impl Into<JObject>) -> JObject {
        let x = x.into();
        let l = deque(self);
        if l.kind == ListKind::Immutable {
            unsupported();
        }
        let mut items = l.items.borrow_mut();
        let i = check_index(index, items.len());
        std::mem::replace(&mut items[i], x)
    }

    fn add_at(&self, index: i32, x: impl Into<JObject>) {
        let x = x.into();
        let l = deque(self);
        l.check_modifiable();
        let mut items = l.items.borrow_mut();
        if index < 0 || index as usize > items.len() {
            index_oob(index, items.len());
        }
        items.insert(index as usize, x);
    }

    fn remove_at(&self, index: i32) -> JObject {
        let l = deque(self);
        l.check_modifiable();
        let mut items = l.items.borrow_mut();
        let i = check_index(index, items.len());
        items.remove(i).unwrap()
    }

    fn index_of(&self, x: impl Into<JObject>) -> i32 {
        let x = x.into();
        deque(self).snapshot().iter().position(|e| equals_nullable(&x, e)).map_or(-1, |i| i as i32)
    }

    fn last_index_of(&self, x: impl Into<JObject>) -> i32 {
        let x = x.into();
        deque(self).snapshot().iter().rposition(|e| equals_nullable(&x, e)).map_or(-1, |i| i as i32)
    }

    fn sort(&self, comparator: &JObject) {
        let l = deque(self);
        if l.kind == ListKind::Immutable {
            unsupported();
        }
        let mut v = l.snapshot();
        v.sort_by(|a, b| compare_with(comparator, a, b).cmp(&0));
        *l.items.borrow_mut() = v.into();
    }

    fn sub_list(&self, from: i32, to: i32) -> JObject {
        let v = deque(self).snapshot();
        if from < 0 || to as usize > v.len() || from > to {
            throw("java.lang.IndexOutOfBoundsException", Some(&format!("fromIndex: {from}, toIndex: {to}, size: {}", v.len())));
        }
        list_from_vec(ListKind::ArrayList, v[from as usize..to as usize].to_vec())
    }

    fn replace_all(&self, op: &JObject) {
        let l = deque(self);
        let v: Vec<JObject> = l.snapshot().into_iter().map(|x| op.invoke("apply", &[x])).collect();
        *l.items.borrow_mut() = v.into();
    }

    fn get_first(&self) -> JObject {
        if self.is::<JSet>() {
            return self.first();
        }
        deque(self).items.borrow().front().cloned().unwrap_or_else(|| no_such_element())
    }

    fn get_last(&self) -> JObject {
        if self.is::<JSet>() {
            return self.last();
        }
        deque(self).items.borrow().back().cloned().unwrap_or_else(|| no_such_element())
    }

    fn remove_first(&self) -> JObject {
        if self.is::<JSet>() {
            let x = self.first();
            self.remove(x.clone());
            return x;
        }
        let l = deque(self);
        l.check_modifiable();
        let x = l.items.borrow_mut().pop_front();
        x.unwrap_or_else(|| no_such_element())
    }

    fn remove_last(&self) -> JObject {
        if self.is::<JSet>() {
            let x = self.last();
            self.remove(x.clone());
            return x;
        }
        let l = deque(self);
        l.check_modifiable();
        let x = l.items.borrow_mut().pop_back();
        x.unwrap_or_else(|| no_such_element())
    }

    fn offer(&self, x: impl Into<JObject>) -> bool {
        self.add(x)
    }

    fn poll(&self) -> JObject {
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return q.poll();
        }
        self.poll_first()
    }

    fn peek(&self) -> JObject {
        if let Some(q) = self.downcast_ref::<JPriorityQueue>() {
            return q.heap.borrow().first().cloned().unwrap_or_default();
        }
        let l = deque(self);
        if l.kind == ListKind::Stack {
            if l.items.borrow().is_empty() {
                throw("java.util.EmptyStackException", None);
            }
            return self.peek_last();
        }
        self.peek_first()
    }

    fn element(&self) -> JObject {
        let x = self.peek();
        if x.is_null() && self.is_empty() {
            no_such_element();
        }
        x
    }

    fn remove_head(&self) -> JObject {
        if self.is::<JPriorityQueue>() {
            if self.is_empty() {
                no_such_element();
            }
            return self.poll();
        }
        self.remove_first()
    }

    fn push(&self, x: impl Into<JObject>) -> JObject {
        let x = x.into();
        let l = deque(self);
        if l.kind == ListKind::Stack {
            l.items.borrow_mut().push_back(x.clone());
        } else {
            self.add_first(x.clone());
        }
        x
    }

    fn pop(&self) -> JObject {
        let l = deque(self);
        if l.kind == ListKind::Stack {
            let x = l.items.borrow_mut().pop_back();
            return x.unwrap_or_else(|| throw("java.util.EmptyStackException", None));
        }
        self.remove_first()
    }

    fn add_first(&self, x: impl Into<JObject>) {
        let x = x.into();
        let l = deque(self);
        l.check_modifiable();
        if l.kind == ListKind::ArrayDeque && x.is_null() {
            throw("java.lang.NullPointerException", None);
        }
        l.items.borrow_mut().push_front(x);
    }

    fn add_last(&self, x: impl Into<JObject>) {
        let x = x.into();
        let l = deque(self);
        l.check_modifiable();
        if l.kind == ListKind::ArrayDeque && x.is_null() {
            throw("java.lang.NullPointerException", None);
        }
        l.items.borrow_mut().push_back(x);
    }

    fn offer_first(&self, x: impl Into<JObject>) -> bool {
        self.add_first(x);
        true
    }

    fn offer_last(&self, x: impl Into<JObject>) -> bool {
        self.add_last(x);
        true
    }

    fn poll_first(&self) -> JObject {
        if let Some(s) = self.downcast_ref::<JSet>() {
            let first = s.tree().borrow().entries.first().map(|e| e.key.clone());
            if let Some(x) = &first {
                s.remove(x);
            }
            return first.unwrap_or_default();
        }
        let l = deque(self);
        let x = l.items.borrow_mut().pop_front();
        x.unwrap_or_default()
    }

    fn poll_last(&self) -> JObject {
        if let Some(s) = self.downcast_ref::<JSet>() {
            let last = s.tree().borrow().entries.last().map(|e| e.key.clone());
            if let Some(x) = &last {
                s.remove(x);
            }
            return last.unwrap_or_default();
        }
        let l = deque(self);
        let x = l.items.borrow_mut().pop_back();
        x.unwrap_or_default()
    }

    fn peek_first(&self) -> JObject {
        deque(self).items.borrow().front().cloned().unwrap_or_default()
    }

    fn peek_last(&self) -> JObject {
        deque(self).items.borrow().back().cloned().unwrap_or_default()
    }

    fn first(&self) -> JObject {
        let s = self.downcast_ref::<JSet>().unwrap_or_else(|| unsupported());
        let x = s.tree().borrow().entries.first().map(|e| e.key.clone());
        x.unwrap_or_else(|| no_such_element())
    }

    fn last(&self) -> JObject {
        let s = self.downcast_ref::<JSet>().unwrap_or_else(|| unsupported());
        let x = s.tree().borrow().entries.last().map(|e| e.key.clone());
        x.unwrap_or_else(|| no_such_element())
    }

    fn floor(&self, x: impl Into<JObject>) -> JObject {
        navigate(self.downcast_ref::<JSet>().map(|s| s.tree()), &x.into(), Nav::Floor).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn ceiling(&self, x: impl Into<JObject>) -> JObject {
        navigate(self.downcast_ref::<JSet>().map(|s| s.tree()), &x.into(), Nav::Ceiling).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn higher(&self, x: impl Into<JObject>) -> JObject {
        navigate(self.downcast_ref::<JSet>().map(|s| s.tree()), &x.into(), Nav::Higher).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn lower(&self, x: impl Into<JObject>) -> JObject {
        navigate(self.downcast_ref::<JSet>().map(|s| s.tree()), &x.into(), Nav::Lower).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn head_set(&self, to: impl Into<JObject>, inclusive: bool) -> JObject {
        let s = self.downcast_ref::<JSet>().unwrap_or_else(|| unsupported());
        let to = to.into();
        let t = s.tree().borrow();
        let out = new_tree_set(t.comparator.clone());
        for e in t.entries.iter() {
            let c = compare_with(&t.comparator, &e.key, &to);
            if c < 0 || (inclusive && c == 0) {
                out.add(e.key.clone());
            }
        }
        out
    }

    fn tail_set(&self, from: impl Into<JObject>, inclusive: bool) -> JObject {
        let s = self.downcast_ref::<JSet>().unwrap_or_else(|| unsupported());
        let from = from.into();
        let t = s.tree().borrow();
        let out = new_tree_set(t.comparator.clone());
        for e in t.entries.iter() {
            let c = compare_with(&t.comparator, &e.key, &from);
            if c > 0 || (inclusive && c == 0) {
                out.add(e.key.clone());
            }
        }
        out
    }

    fn map_get(&self, key: impl Into<JObject>) -> JObject {
        let key = key.into();
        match self.downcast_ref::<JMap>() {
            Some(m) => m.find(&key).map(|e| e.value()).unwrap_or_default(),
            None => call(self, "get", &[key]),
        }
    }

    fn put(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JObject {
        map(self).put(key.into(), value.into())
    }

    fn contains_key(&self, key: impl Into<JObject>) -> bool {
        let key = key.into();
        match self.downcast_ref::<JMap>() {
            Some(m) => m.find(&key).is_some(),
            None => call(self, "containsKey", &[key]).unbox_bool(),
        }
    }

    fn contains_value(&self, value: impl Into<JObject>) -> bool {
        let v = value.into();
        map(self).entries().iter().any(|e| equals_nullable(&v, &e.value()))
    }

    fn map_remove(&self, key: impl Into<JObject>) -> JObject {
        map(self).remove(&key.into()).map(|e| e.value()).unwrap_or_default()
    }

    fn get_or_default(&self, key: impl Into<JObject>, default: impl Into<JObject>) -> JObject {
        match map(self).find(&key.into()) {
            Some(e) => e.value(),
            None => default.into(),
        }
    }

    fn put_if_absent(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JObject {
        let key = key.into();
        let m = map(self);
        match m.find(&key) {
            Some(e) if !e.value().is_null() => e.value(),
            _ => {
                m.put(key, value.into());
                JObject::null()
            }
        }
    }

    fn put_all(&self, other: &JObject) {
        let m = map(self);
        let entries = map_entries(other);
        if let MapImpl::Hash(c) = &m.imp {
            c.borrow_mut().presize(entries.len());
        }
        for (k, v) in entries {
            m.put(k, v);
        }
    }

    fn key_set(&self) -> JObject {
        let keys: VecDeque<JObject> = map(self).entries().iter().map(|e| e.key.clone()).collect();
        new_list_of(ListKind::KeySet, keys, self.clone())
    }

    fn values(&self) -> JObject {
        let vals: VecDeque<JObject> = map(self).entries().iter().map(|e| e.value()).collect();
        new_list_of(ListKind::Values, vals, self.clone())
    }

    fn entry_set(&self) -> JObject {
        let es: VecDeque<JObject> = map(self).entries().iter().map(|e| e.as_object()).collect();
        new_list_of(ListKind::EntrySet, es, self.clone())
    }

    fn compute_if_absent(&self, key: impl Into<JObject>, f: &JObject) -> JObject {
        let key = key.into();
        let m = map(self);
        m.begin_compute();
        let existing = m.find(&key);
        if let Some(e) = &existing {
            if !e.value().is_null() {
                return e.value();
            }
        }
        let v = f.invoke("apply", &[key.clone()]);
        if !v.is_null() {
            match existing {
                Some(e) => {
                    e.set_value(v.clone());
                }
                None => m.insert_computed(key, v.clone()),
            }
        }
        v
    }

    fn compute_if_present(&self, key: impl Into<JObject>, f: &JObject) -> JObject {
        let key = key.into();
        let m = map(self);
        match m.find(&key) {
            Some(e) if !e.value().is_null() => {
                let v = f.invoke("apply", &[key.clone(), e.value()]);
                if v.is_null() {
                    m.remove(&key);
                } else {
                    e.set_value(v.clone());
                }
                v
            }
            _ => JObject::null(),
        }
    }

    fn compute(&self, key: impl Into<JObject>, f: &JObject) -> JObject {
        let key = key.into();
        let m = map(self);
        m.begin_compute();
        let existing = m.find(&key);
        let old = existing.as_ref().map(|e| e.value()).unwrap_or_default();
        let v = f.invoke("apply", &[key.clone(), old]);
        match (existing, v.is_null()) {
            (Some(_), true) => {
                m.remove(&key);
            }
            (Some(e), false) => {
                e.set_value(v.clone());
            }
            (None, false) => m.insert_computed(key, v.clone()),
            (None, true) => {}
        }
        v
    }

    fn merge(&self, key: impl Into<JObject>, value: impl Into<JObject>, f: &JObject) -> JObject {
        let (key, value) = (key.into(), value.into());
        if value.is_null() {
            throw("java.lang.NullPointerException", None);
        }
        let m = map(self);
        m.begin_compute();
        match m.find(&key) {
            Some(e) if !e.value().is_null() => {
                let v = f.invoke("apply", &[e.value(), value]);
                if v.is_null() {
                    m.remove(&key);
                } else {
                    e.set_value(v.clone());
                }
                v
            }
            Some(e) => {
                e.set_value(value.clone());
                value
            }
            None => {
                m.insert_computed(key, value.clone());
                value
            }
        }
    }

    fn map_for_each(&self, action: &JObject) {
        for (k, v) in map_entries(self) {
            action.invoke("accept", &[k, v]);
        }
    }

    fn map_replace(&self, key: impl Into<JObject>, value: impl Into<JObject>) -> JObject {
        let key = key.into();
        match map(self).find(&key) {
            Some(e) => e.set_value(value.into()),
            None => JObject::null(),
        }
    }

    fn first_key(&self) -> JObject {
        let x = map(self).tree().borrow().entries.first().map(|e| e.key.clone());
        x.unwrap_or_else(|| no_such_element())
    }

    fn last_key(&self) -> JObject {
        let x = map(self).tree().borrow().entries.last().map(|e| e.key.clone());
        x.unwrap_or_else(|| no_such_element())
    }

    fn floor_key(&self, key: impl Into<JObject>) -> JObject {
        navigate(Some(map(self).tree()), &key.into(), Nav::Floor).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn ceiling_key(&self, key: impl Into<JObject>) -> JObject {
        navigate(Some(map(self).tree()), &key.into(), Nav::Ceiling).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn higher_key(&self, key: impl Into<JObject>) -> JObject {
        navigate(Some(map(self).tree()), &key.into(), Nav::Higher).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn lower_key(&self, key: impl Into<JObject>) -> JObject {
        navigate(Some(map(self).tree()), &key.into(), Nav::Lower).map(|e| e.key.clone()).unwrap_or_default()
    }

    fn first_entry(&self) -> JObject {
        let x = map(self).tree().borrow().entries.first().cloned();
        x.map(|e| e.as_object()).unwrap_or_default()
    }

    fn last_entry(&self) -> JObject {
        let x = map(self).tree().borrow().entries.last().cloned();
        x.map(|e| e.as_object()).unwrap_or_default()
    }

    fn poll_first_entry(&self) -> JObject {
        let t = map(self).tree();
        let x = if t.borrow().entries.is_empty() { None } else { Some(t.borrow_mut().entries.remove(0)) };
        x.map(|e| e.as_object()).unwrap_or_default()
    }

    fn poll_last_entry(&self) -> JObject {
        let t = map(self).tree();
        let x = t.borrow_mut().entries.pop();
        x.map(|e| e.as_object()).unwrap_or_default()
    }

    fn floor_entry(&self, key: impl Into<JObject>) -> JObject {
        navigate(Some(map(self).tree()), &key.into(), Nav::Floor).map(|e| e.as_object()).unwrap_or_default()
    }

    fn ceiling_entry(&self, key: impl Into<JObject>) -> JObject {
        navigate(Some(map(self).tree()), &key.into(), Nav::Ceiling).map(|e| e.as_object()).unwrap_or_default()
    }

    fn head_map(&self, to: impl Into<JObject>, inclusive: bool) -> JObject {
        let to = to.into();
        let t = map(self).tree().borrow();
        let out = new_tree_map(t.comparator.clone());
        for e in t.entries.iter() {
            let c = compare_with(&t.comparator, &e.key, &to);
            if c < 0 || (inclusive && c == 0) {
                out.put(e.key.clone(), e.value());
            }
        }
        out
    }

    fn tail_map(&self, from: impl Into<JObject>, inclusive: bool) -> JObject {
        let from = from.into();
        let t = map(self).tree().borrow();
        let out = new_tree_map(t.comparator.clone());
        for e in t.entries.iter() {
            let c = compare_with(&t.comparator, &e.key, &from);
            if c > 0 || (inclusive && c == 0) {
                out.put(e.key.clone(), e.value());
            }
        }
        out
    }

    fn get_key(&self) -> JObject {
        match self.downcast_ref::<MapEntry>() {
            Some(e) => e.key(),
            None => call(self, "getKey", &[]),
        }
    }

    fn get_value(&self) -> JObject {
        match self.downcast_ref::<MapEntry>() {
            Some(e) => e.value(),
            None => call(self, "getValue", &[]),
        }
    }

    fn set_value(&self, value: impl Into<JObject>) -> JObject {
        match self.downcast_ref::<MapEntry>() {
            Some(e) => e.set_value(value.into()),
            None => call(self, "setValue", &[value.into()]),
        }
    }

    fn has_next(&self) -> bool {
        match self.downcast_ref::<JIterator>() {
            Some(it) => it.pos.get() < it.items.len(),
            None => call(self, "hasNext", &[]).unbox_bool(),
        }
    }

    fn next(&self) -> JObject {
        match self.downcast_ref::<JIterator>() {
            Some(it) => {
                let p = it.pos.get();
                if p >= it.items.len() {
                    no_such_element();
                }
                it.pos.set(p + 1);
                it.can_remove.set(true);
                it.items[p].clone()
            }
            None => call(self, "next", &[]),
        }
    }

    fn iter_remove(&self) {
        match self.downcast_ref::<JIterator>() {
            Some(it) => it.remove(),
            None => {
                call(self, "remove", &[]);
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

fn navigate(tree: Option<&RefCell<TreeCore>>, key: &JObject, nav: Nav) -> Option<Rc<MapEntry>> {
    let t = tree.unwrap_or_else(|| unsupported()).borrow();
    let n = t.entries.len();
    let idx: Option<usize> = match (t.search(key), nav) {
        (Ok(i), Nav::Floor) | (Ok(i), Nav::Ceiling) => Some(i),
        (Ok(i), Nav::Higher) => (i + 1 < n).then_some(i + 1),
        (Ok(i), Nav::Lower) => i.checked_sub(1),
        (Err(i), Nav::Floor) | (Err(i), Nav::Lower) => i.checked_sub(1),
        (Err(i), Nav::Ceiling) | (Err(i), Nav::Higher) => (i < n).then_some(i),
    };
    idx.map(|i| t.entries[i].clone())
}

/// `Map.entry(k, v)`。
pub fn map_entry(key: impl Into<JObject>, value: impl Into<JObject>) -> JObject {
    new_entry(key.into(), value.into())
}
