//! マップ・集合の内部実装。
//!
//! [`HashCore`] は java.util.HashMap と同じアルゴリズム（ハッシュの拡散、2 のべき乗の表、負荷率 0.75、
//! リサイズ時にバケット内の順序を保った分割）で、反復順を Java と一致させる。
//! [`TreeCore`] は比較関数で整列した配列（TreeMap / TreeSet 用）。

use crate::lang::string::JString;
use crate::object::{alloc_rc, equals_nullable, hash_nullable, JObject, Object, ObjectBase};
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
    fn to_jstring(&self) -> JString {
        crate::jconcat!(self.key, "=", self.value())
    }
    fn equals(&self, other: &JObject) -> bool {
        match other.downcast_ref::<MapEntry>() {
            Some(o) => equals_nullable(&self.key, &o.key) && equals_nullable(&self.value(), &o.value()),
            None => false,
        }
    }
    fn hash_code(&self) -> i32 {
        hash_nullable(&self.key) ^ hash_nullable(&self.value())
    }
    fn invoke(&self, method: &str, args: &[JObject]) -> Option<JObject> {
        match method {
            "getKey" => Some(self.key()),
            "getValue" => Some(self.value()),
            "setValue" => Some(self.set_value(args[0].clone())),
            _ => None,
        }
    }
}

/// `Map.entry(k, v)` など、マップに属さない Entry。
pub fn new_entry(key: JObject, value: JObject) -> JObject {
    MapEntry::new(0, key, value).as_object()
}

/// HashMap.hash(): `h ^ (h >>> 16)`。
pub(crate) fn spread(key: &JObject) -> i32 {
    let h = hash_nullable(key);
    h ^ ((h as u32) >> 16) as i32
}

fn keys_equal(a: &JObject, b: &JObject) -> bool {
    a.same(b) || (!a.is_null() && a.equals(b))
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
}

impl HashCore {
    pub(crate) fn new(linked: bool) -> HashCore {
        HashCore { table: Vec::new(), size: 0, threshold: 0, order: if linked { Some(Vec::new()) } else { None } }
    }

    /// `new HashMap(initialCapacity)`。
    pub(crate) fn with_capacity(linked: bool, cap: i32) -> HashCore {
        if cap < 0 {
            crate::rt::throw("java.lang.IllegalArgumentException", Some(&format!("Illegal initial capacity: {cap}")));
        }
        let mut c = HashCore::new(linked);
        c.threshold = table_size_for(cap as usize);
        c
    }

    pub(crate) fn len(&self) -> usize {
        self.size
    }

    /// 同じキーの要素（equals の呼び出しはユーザーのコードを実行しうるので、呼び出し側は共有借用で呼ぶ）。
    pub(crate) fn find(&self, key: &JObject, hash: i32) -> Option<Rc<MapEntry>> {
        if self.table.is_empty() {
            return None;
        }
        let idx = (self.table.len() - 1) & hash as u32 as usize;
        self.table[idx].iter().find(|e| e.hash == hash && keys_equal(key, &e.key)).cloned()
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
        if self.size > self.threshold {
            self.resize();
        }
    }

    /// merge / compute / computeIfAbsent の最初に行うリサイズ（Java はこれらの操作の冒頭で
    /// `size > threshold` なら表を広げ、新しい要素はバケットの先頭に入れる）。
    pub(crate) fn pre_resize_for_compute(&mut self) {
        if self.table.is_empty() || self.size > self.threshold {
            self.resize();
        }
    }

    /// merge / compute 系で新しい要素を入れる（バケットの先頭。リサイズは次の操作の冒頭で行う）。
    pub(crate) fn insert_new_head(&mut self, entry: Rc<MapEntry>) {
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

    pub(crate) fn remove(&mut self, key: &JObject, hash: i32) -> Option<Rc<MapEntry>> {
        if self.table.is_empty() {
            return None;
        }
        let idx = (self.table.len() - 1) & hash as u32 as usize;
        let pos = self.table[idx].iter().position(|e| e.hash == hash && keys_equal(key, &e.key))?;
        let e = self.table[idx].remove(pos);
        if let Some(o) = &mut self.order {
            if let Some(p) = o.iter().position(|x| Rc::ptr_eq(x, &e)) {
                o.remove(p);
            }
        }
        self.size -= 1;
        Some(e)
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
pub fn compare_with(cmp: &JObject, a: &JObject, b: &JObject) -> i32 {
    if cmp.is_null() {
        a.compare_to(b)
    } else {
        cmp.invoke("compare", &[a.clone(), b.clone()]).unbox_i32()
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
    pub(crate) fn search(&self, key: &JObject) -> Result<usize, usize> {
        if key.is_null() && self.comparator.is_null() {
            crate::rt::throw("java.lang.NullPointerException", None);
        }
        let (mut lo, mut hi) = (0usize, self.entries.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            let c = compare_with(&self.comparator, &self.entries[mid].key, key);
            if c < 0 {
                lo = mid + 1;
            } else if c > 0 {
                hi = mid;
            } else {
                return Ok(mid);
            }
        }
        Err(lo)
    }
}
