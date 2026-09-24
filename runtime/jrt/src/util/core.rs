//! マップ・集合の内部実装。
//!
//! [`HashCore`] は java.util.HashMap と同じアルゴリズム（ハッシュの拡散、2 のべき乗の表、負荷率 0.75、
//! リサイズ時にバケット内の順序を保った分割）で、反復順を Java と一致させる。
//! [`TreeCore`] は比較関数で整列した配列（TreeMap / TreeSet 用）。

use crate::lang::string::JString;
use crate::object::{alloc_rc, equals_nullable, hash_nullable, JObject, Object, ObjectBase};
use crate::rt::JResult;
use std::cell::RefCell;
use std::rc::Rc;

/// `Map.Entry`（マップの要素そのもの。`setValue` はマップに反映される）。
pub struct MapEntry {
    base: ObjectBase,
    pub(crate) hash: i32,
    pub(crate) key: JObject,
    pub(crate) value: RefCell<JObject>,
}

impl MapEntry {
    pub(crate) fn new(hash: i32, key: JObject, value: JObject) -> Rc<MapEntry> {
        alloc_rc(|base| MapEntry { base, hash, key, value: RefCell::new(value) })
    }

    pub fn key(&self) -> JObject {
        self.key.clone()
    }

    pub fn value(&self) -> JObject {
        self.value.borrow().clone()
    }

    pub fn set_value(&self, v: JObject) -> JObject {
        std::mem::replace(&mut *self.value.borrow_mut(), v)
    }

    pub(crate) fn as_object(self: &Rc<Self>) -> JObject {
        JObject::from_rc(self.clone())
    }
}

impl Object for MapEntry {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.HashMap$Node"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.Map$Entry")
    }
    fn to_jstring(&self) -> JResult<JString> {
        let (k, v) = (crate::lang::stringify::to_java_string(&self.key)?, crate::lang::stringify::to_java_string(&self.value())?);
        Ok(JString::from(format!("{k}={v}")))
    }
    fn equals(&self, other: &JObject) -> JResult<bool> {
        match other.downcast_ref::<MapEntry>() {
            Some(o) => Ok(equals_nullable(&self.key, &o.key)? && equals_nullable(&self.value(), &o.value())?),
            None => Ok(false),
        }
    }
    fn hash_code(&self) -> JResult<i32> {
        Ok(hash_nullable(&self.key)? ^ hash_nullable(&self.value())?)
    }
    fn invoke(&self, method: &str, args: &[JObject]) -> JResult<Option<JObject>> {
        Ok(match (method, args.len()) {
            ("getKey", 0) => Some(self.key()),
            ("getValue", 0) => Some(self.value()),
            ("setValue", 1) => Some(self.set_value(args[0].clone())),
            _ => None,
        })
    }
}

/// `Map.entry(k, v)` など、マップに属さない Entry。
pub fn new_entry(key: JObject, value: JObject) -> JObject {
    MapEntry::new(0, key, value).as_object()
}

/// HashMap.hash(): `h ^ (h >>> 16)`。
pub(crate) fn spread(key: &JObject) -> JResult<i32> {
    let h = hash_nullable(key)?;
    Ok(h ^ ((h as u32) >> 16) as i32)
}

fn keys_equal(a: &JObject, b: &JObject) -> JResult<bool> {
    Ok(a.same(b) || (!a.is_null() && a.equals(b)?))
}

/// `tableSizeFor(cap)`: cap 以上の最小の 2 のべき乗。
pub(crate) fn table_size_for(cap: usize) -> usize {
    cap.max(1).next_power_of_two().min(1 << 30)
}

/// java.util.HashMap の表。`linked` なら挿入順（LinkedHashMap）も保持する。
pub(crate) struct HashCore {
    table: Vec<Vec<Rc<MapEntry>>>,
    size: usize,
    threshold: usize,
    order: Option<Vec<Rc<MapEntry>>>,
    /// ConcurrentHashMap（compute / merge でもバケットの末尾に入れ、要素数が 0.75n に達したら広げる）。
    concurrent: bool,
}

impl HashCore {
    pub(crate) fn new(linked: bool) -> HashCore {
        HashCore { table: Vec::new(), size: 0, threshold: 0, order: if linked { Some(Vec::new()) } else { None }, concurrent: false }
    }

    /// ConcurrentHashMap の表。
    pub(crate) fn new_concurrent() -> HashCore {
        HashCore { concurrent: true, ..HashCore::new(false) }
    }

    /// `new HashMap(initialCapacity)`。
    pub(crate) fn with_capacity(linked: bool, cap: i32) -> JResult<HashCore> {
        if cap < 0 {
            return crate::rt::throw("java.lang.IllegalArgumentException", Some(&format!("Illegal initial capacity: {cap}")));
        }
        let mut c = HashCore::new(linked);
        c.threshold = table_size_for(cap as usize);
        Ok(c)
    }

    pub(crate) fn len(&self) -> usize {
        self.size
    }

    /// 同じキーの要素（equals の呼び出しはユーザーのコードを実行しうるので、呼び出し側は共有借用で呼ぶ）。
    pub(crate) fn find(&self, key: &JObject, hash: i32) -> JResult<Option<Rc<MapEntry>>> {
        if self.table.is_empty() {
            return Ok(None);
        }
        let idx = (self.table.len() - 1) & hash as u32 as usize;
        for e in &self.table[idx] {
            if e.hash == hash && keys_equal(key, &e.key)? {
                return Ok(Some(e.clone()));
            }
        }
        Ok(None)
    }

    /// 新しい要素を追加する（同じキーがないことは呼び出し側が確かめる）。
    pub(crate) fn insert_new(&mut self, entry: Rc<MapEntry>) {
        if self.table.is_empty() {
            self.resize();
        }
        let idx = (self.table.len() - 1) & entry.hash as u32 as usize;
        self.table[idx].push(entry.clone());
        if let Some(o) = &mut self.order {
            o.push(entry);
        }
        self.size += 1;
        if self.size > self.threshold || (self.concurrent && self.size >= self.threshold) {
            self.resize();
        }
    }

    /// merge / compute / computeIfAbsent の最初に行うリサイズ（Java はこれらの操作の冒頭で
    /// `size > threshold` なら表を広げ、新しい要素はバケットの先頭に入れる）。
    pub(crate) fn pre_resize_for_compute(&mut self) {
        if self.concurrent {
            return;
        }
        if self.table.is_empty() || self.size > self.threshold {
            self.resize();
        }
    }

    /// merge / compute 系で新しい要素を入れる（バケットの先頭。リサイズは次の操作の冒頭で行う）。
    pub(crate) fn insert_new_head(&mut self, entry: Rc<MapEntry>) {
        if self.concurrent {
            return self.insert_new(entry);
        }
        if self.table.is_empty() {
            self.resize();
        }
        let idx = (self.table.len() - 1) & entry.hash as u32 as usize;
        self.table[idx].insert(0, entry.clone());
        if let Some(o) = &mut self.order {
            o.push(entry);
        }
        self.size += 1;
    }

    /// `putMapEntries` / `HashSet(Collection)` の事前拡張: 要素数 n を入れても再ハッシュしない大きさにする。
    pub(crate) fn presize(&mut self, n: usize) {
        if self.table.is_empty() {
            let t = (n as f64 / 0.75 + 1.0) as usize;
            let t = table_size_for(t);
            if t > self.threshold {
                self.threshold = t;
            }
        } else {
            while n > self.threshold && self.table.len() < (1 << 30) {
                self.resize();
            }
        }
    }

    fn resize(&mut self) {
        let old_cap = self.table.len();
        let new_cap = if old_cap > 0 {
            old_cap << 1
        } else if self.threshold > 0 {
            self.threshold
        } else {
            16
        };
        self.threshold = (new_cap as f64 * 0.75) as usize;
        let mut new_table: Vec<Vec<Rc<MapEntry>>> = vec![Vec::new(); new_cap];
        if old_cap > 0 {
            for (j, bucket) in std::mem::take(&mut self.table).into_iter().enumerate() {
                for e in bucket {
                    // Java と同じく、バケット内の順序を保ったまま lo（j）と hi（j + oldCap）に分ける。
                    if (e.hash as u32 as usize) & old_cap == 0 {
                        new_table[j].push(e);
                    } else {
                        new_table[j + old_cap].push(e);
                    }
                }
            }
        }
        self.table = new_table;
    }

    /// 要素を取り除く（find で見つけた要素。equals を呼ぶ探索とは分けて、可変借用中にユーザーのコードを実行しない）。
    pub(crate) fn remove_entry(&mut self, e: &Rc<MapEntry>) {
        if self.table.is_empty() {
            return;
        }
        let idx = (self.table.len() - 1) & e.hash as u32 as usize;
        let Some(pos) = self.table[idx].iter().position(|x| Rc::ptr_eq(x, e)) else { return };
        self.table[idx].remove(pos);
        if let Some(o) = &mut self.order {
            if let Some(p) = o.iter().position(|x| Rc::ptr_eq(x, e)) {
                o.remove(p);
            }
        }
        self.size -= 1;
    }

    pub(crate) fn clear(&mut self) {
        for b in &mut self.table {
            b.clear();
        }
        if let Some(o) = &mut self.order {
            o.clear();
        }
        self.size = 0;
    }

    /// 反復順（HashMap は表の順、LinkedHashMap は挿入順）の要素。
    pub(crate) fn entries(&self) -> Vec<Rc<MapEntry>> {
        match &self.order {
            Some(o) => o.clone(),
            None => self.table.iter().flat_map(|b| b.iter().cloned()).collect(),
        }
    }
}

/// 比較関数（null なら自然順序 = compareTo）。
pub fn compare_with(cmp: &JObject, a: &JObject, b: &JObject) -> JResult<i32> {
    if cmp.is_null() {
        a.compare_to(b)
    } else {
        cmp.invoke("compare", &[a.clone(), b.clone()])?.unbox_i32()
    }
}

/// TreeMap / TreeSet の中身（キーで整列した配列）。
pub(crate) struct TreeCore {
    pub(crate) comparator: JObject,
    pub(crate) entries: Vec<Rc<MapEntry>>,
}

impl TreeCore {
    pub(crate) fn new(comparator: JObject) -> TreeCore {
        TreeCore { comparator, entries: Vec::new() }
    }

    /// 二分探索（Ok: 一致した位置、Err: 挿入位置）。
    pub(crate) fn search(&self, key: &JObject) -> JResult<Result<usize, usize>> {
        if key.is_null() && self.comparator.is_null() {
            return crate::rt::npe();
        }
        let (mut lo, mut hi) = (0usize, self.entries.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            let c = compare_with(&self.comparator, &self.entries[mid].key, key)?;
            if c < 0 {
                lo = mid + 1;
            } else if c > 0 {
                hi = mid;
            } else {
                return Ok(Ok(mid));
            }
        }
        Ok(Err(lo))
    }
}

/// 安定なソート（マージソート）。比較関数が例外を送出したら、その時点で止めて `Err` を返す（v は変更しない）。
pub(crate) fn try_sort_by<T: Clone>(v: &mut Vec<T>, mut cmp: impl FnMut(&T, &T) -> JResult<std::cmp::Ordering>) -> JResult<()> {
    fn merge_sort<T: Clone>(v: &[T], cmp: &mut dyn FnMut(&T, &T) -> JResult<std::cmp::Ordering>) -> JResult<Vec<T>> {
        if v.len() <= 1 {
            return Ok(v.to_vec());
        }
        let mid = v.len() / 2;
        let left = merge_sort(&v[..mid], cmp)?;
        let right = merge_sort(&v[mid..], cmp)?;
        let mut out = Vec::with_capacity(v.len());
        let (mut i, mut j) = (0, 0);
        while i < left.len() && j < right.len() {
            if cmp(&right[j], &left[i])? == std::cmp::Ordering::Less {
                out.push(right[j].clone());
                j += 1;
            } else {
                out.push(left[i].clone());
                i += 1;
            }
        }
        out.extend_from_slice(&left[i..]);
        out.extend_from_slice(&right[j..]);
        Ok(out)
    }
    *v = merge_sort(v, &mut cmp)?;
    Ok(())
}

/// java.util.Hashtable の表（HashMap と別のアルゴリズム: 容量 11 から 2n+1 で広げ、`(hash & 0x7FFFFFFF) % 容量` の
/// バケットの先頭に入れ、反復は表の後ろのバケットから）。反復順を Java と一致させる。
pub(crate) struct TableCore {
    table: Vec<Vec<Rc<MapEntry>>>,
    count: usize,
    threshold: usize,
}

impl TableCore {
    pub(crate) fn new(cap: usize) -> TableCore {
        let cap = cap.max(1);
        TableCore { table: vec![Vec::new(); cap], count: 0, threshold: ((cap as f64) * 0.75) as usize }
    }

    fn index(hash: i32, len: usize) -> usize {
        ((hash & 0x7FFF_FFFF) as usize) % len
    }

    pub(crate) fn len(&self) -> usize {
        self.count
    }

    /// Hashtable のハッシュは key.hashCode() そのもの（null のキーは NullPointerException）。
    pub(crate) fn hash(key: &JObject) -> JResult<i32> {
        key.hash_code()
    }

    pub(crate) fn find(&self, key: &JObject, hash: i32) -> JResult<Option<Rc<MapEntry>>> {
        for e in &self.table[Self::index(hash, self.table.len())] {
            if e.hash == hash && e.key.equals(key)? {
                return Ok(Some(e.clone()));
            }
        }
        Ok(None)
    }

    pub(crate) fn insert_new(&mut self, entry: Rc<MapEntry>) {
        if self.count >= self.threshold {
            self.rehash();
        }
        let idx = Self::index(entry.hash, self.table.len());
        self.table[idx].insert(0, entry);
        self.count += 1;
    }

    fn rehash(&mut self) {
        let old = std::mem::take(&mut self.table);
        let new_cap = (old.len() << 1) + 1;
        self.threshold = ((new_cap as f64) * 0.75) as usize;
        let mut table: Vec<Vec<Rc<MapEntry>>> = vec![Vec::new(); new_cap];
        for bucket in old.into_iter().rev() {
            for e in bucket {
                let idx = Self::index(e.hash, new_cap);
                table[idx].insert(0, e);
            }
        }
        self.table = table;
    }

    pub(crate) fn remove_entry(&mut self, e: &Rc<MapEntry>) {
        let idx = Self::index(e.hash, self.table.len());
        if let Some(p) = self.table[idx].iter().position(|x| Rc::ptr_eq(x, e)) {
            self.table[idx].remove(p);
            self.count -= 1;
        }
    }

    pub(crate) fn clear(&mut self) {
        for b in &mut self.table {
            b.clear();
        }
        self.count = 0;
    }

    /// 反復順（表の最後のバケットから、各バケットは先頭から）。
    pub(crate) fn entries(&self) -> Vec<Rc<MapEntry>> {
        self.table.iter().rev().flat_map(|b| b.iter().cloned()).collect()
    }
}
