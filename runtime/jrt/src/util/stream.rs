//! java.util.stream（Stream / IntStream / LongStream / DoubleStream と Collectors）。
//!
//! Java と同じく遅延評価する: 各段は「次の要素を 1 つ取り出す」関数で、終端操作が要素を 1 つずつ引き出す。
//! そのため map / filter / peek の実行順（要素ごとに段を通る）、limit / findFirst / anyMatch の打ち切り、
//! 無限ストリーム（iterate / generate）が Java と一致する。sorted だけは上流をすべて読んでから並べる。
//! 要素はボクシングした `JObject` で持ち、IntStream などは種類（[`Kind`]）で区別する。

use crate::array::JArray;
use crate::lang::boxed::{box_bool, box_f64, box_i32, box_i64};
use crate::lang::string::JString;
use crate::object::{alloc, JObject, Object, ObjectBase};
use crate::rt::{npe, throw, JResult};
use crate::util::collections::{new_list, new_map, new_set, Collections as _, ListKind, MapKind, SetKind};
use crate::util::core::{compare_with, try_sort_by};
use crate::util::optional::{self, OptKind};
use std::cell::{Cell, RefCell};

/// ストリームの要素の種類。
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    Obj,
    Int,
    Long,
    Double,
}

impl Kind {
    fn opt(self) -> OptKind {
        match self {
            Kind::Obj => OptKind::Obj,
            Kind::Int => OptKind::Int,
            Kind::Long => OptKind::Long,
            Kind::Double => OptKind::Double,
        }
    }

    /// 要素を同じ種類の要素に写す関数型インタフェースのメソッド名（map / reduce / iterate）。
    fn unary(self) -> &'static str {
        match self {
            Kind::Obj => "apply",
            Kind::Int => "applyAsInt",
            Kind::Long => "applyAsLong",
            Kind::Double => "applyAsDouble",
        }
    }
}

type Pull = Box<dyn FnMut() -> JResult<Option<JObject>>>;

pub struct JStream {
    base: ObjectBase,
    kind: Kind,
    pull: RefCell<Option<Pull>>,
    closed: Cell<bool>,
}

impl Object for JStream {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        match self.kind {
            Kind::Obj => "java.util.stream.ReferencePipeline",
            Kind::Int => "java.util.stream.IntPipeline",
            Kind::Long => "java.util.stream.LongPipeline",
            Kind::Double => "java.util.stream.DoublePipeline",
        }
    }
    fn instance_of(&self, class: &str) -> bool {
        let iface = match self.kind {
            Kind::Obj => "java.util.stream.Stream",
            Kind::Int => "java.util.stream.IntStream",
            Kind::Long => "java.util.stream.LongStream",
            Kind::Double => "java.util.stream.DoubleStream",
        };
        matches!(class, "java.lang.Object" | "java.util.stream.BaseStream" | "java.lang.AutoCloseable") || class == iface
    }
}

fn new_stream(kind: Kind, pull: Pull) -> JObject {
    alloc(|base| JStream { base, kind, pull: RefCell::new(Some(pull)), closed: Cell::new(false) })
}

/// 要素の列からストリームを作る。
pub fn from_vec(kind: Kind, items: Vec<JObject>) -> JObject {
    let mut it = items.into_iter();
    new_stream(kind, Box::new(move || Ok(it.next())))
}

fn stream_of(o: &JObject) -> JResult<&JStream> {
    match o.downcast_ref::<JStream>() {
        Some(s) => Ok(s),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.stream.Stream")
        }
    }
}

/// ストリームを消費する（2 回目は IllegalStateException）。
fn take(o: &JObject) -> JResult<(Kind, Pull)> {
    let s = stream_of(o)?;
    let pull = s.pull.borrow_mut().take();
    match pull {
        Some(p) if !s.closed.get() => Ok((s.kind, p)),
        _ => throw("java.lang.IllegalStateException", Some("stream has already been operated upon or closed")),
    }
}

fn drain(mut pull: Pull) -> JResult<Vec<JObject>> {
    let mut out = Vec::new();
    while let Some(x) = pull()? {
        out.push(x);
    }
    Ok(out)
}

fn check_fn(f: &JObject) -> JResult<JObject> {
    f.obj()?;
    Ok(f.clone())
}

// ------------------------------------------------------------------ 生成

/// `collection.stream()`。
pub fn of_collection(c: &JObject) -> JResult<JObject> {
    Ok(from_vec(Kind::Obj, crate::util::collections::to_vec(c)?))
}

/// `Arrays.stream(T[])` / `Stream.of(a, b, ...)`。
pub fn of_array<T: Clone + Into<JObject>>(a: &JArray<T>) -> JResult<JObject> {
    Ok(from_vec(Kind::Obj, a.to_vec()?.into_iter().map(Into::into).collect()))
}

/// `Stream.of(x)`（要素 1 つ）。
pub fn of_one(x: impl Into<JObject>) -> JObject {
    from_vec(Kind::Obj, vec![x.into()])
}

/// `Stream.ofNullable(x)`。
pub fn of_nullable(x: impl Into<JObject>) -> JObject {
    let x = x.into();
    from_vec(Kind::Obj, if x.is_null() { vec![] } else { vec![x] })
}

/// `Stream.empty()` / `IntStream.empty()` など。
pub fn empty(kind: Kind) -> JObject {
    from_vec(kind, Vec::new())
}

/// `Arrays.stream(int[])` / `IntStream.of(...)`。
pub fn of_ints(a: &JArray<i32>) -> JResult<JObject> {
    Ok(from_vec(Kind::Int, a.to_vec()?.into_iter().map(box_i32).collect()))
}

/// `Arrays.stream(long[])` / `LongStream.of(...)`。
pub fn of_longs(a: &JArray<i64>) -> JResult<JObject> {
    Ok(from_vec(Kind::Long, a.to_vec()?.into_iter().map(box_i64).collect()))
}

/// `Arrays.stream(double[])` / `DoubleStream.of(...)`。
pub fn of_doubles(a: &JArray<f64>) -> JResult<JObject> {
    Ok(from_vec(Kind::Double, a.to_vec()?.into_iter().map(box_f64).collect()))
}

/// `IntStream.range(a, b)` / `rangeClosed`。
pub fn int_range(from: i32, to: i32, closed: bool) -> JObject {
    let end = if closed { to as i64 + 1 } else { to as i64 };
    let mut i = from as i64;
    new_stream(Kind::Int, Box::new(move || {
        if i >= end {
            return Ok(None);
        }
        i += 1;
        Ok(Some(box_i32((i - 1) as i32)))
    }))
}

/// `LongStream.range(a, b)` / `rangeClosed`。
pub fn long_range(from: i64, to: i64, closed: bool) -> JObject {
    let end = if closed { to as i128 + 1 } else { to as i128 };
    let mut i = from as i128;
    new_stream(Kind::Long, Box::new(move || {
        if i >= end {
            return Ok(None);
        }
        i += 1;
        Ok(Some(box_i64((i - 1) as i64)))
    }))
}

/// `Stream.iterate(seed, f)`（無限）/ `IntStream.iterate(seed, f)`。
pub fn iterate(kind: Kind, seed: impl Into<JObject>, f: &JObject) -> JResult<JObject> {
    let f = check_fn(f)?;
    let mut next: Option<JObject> = Some(seed.into());
    let mut started = false;
    Ok(new_stream(kind, Box::new(move || {
        if started {
            let cur = next.take().unwrap_or_default();
            next = Some(f.invoke(kind.unary(), &[cur])?);
        }
        started = true;
        Ok(next.clone())
    })))
}

/// `Stream.iterate(seed, hasNext, next)`。
pub fn iterate_while(kind: Kind, seed: impl Into<JObject>, has_next: &JObject, f: &JObject) -> JResult<JObject> {
    let (has_next, f) = (check_fn(has_next)?, check_fn(f)?);
    let mut cur: Option<JObject> = None;
    let mut seed = Some(seed.into());
    let mut done = false;
    Ok(new_stream(kind, Box::new(move || {
        if done {
            return Ok(None);
        }
        let candidate = match seed.take() {
            Some(s) => s,
            None => f.invoke(kind.unary(), &[cur.clone().unwrap_or_default()])?,
        };
        if !has_next.invoke("test", &[candidate.clone()])?.unbox_bool()? {
            done = true;
            return Ok(None);
        }
        cur = Some(candidate.clone());
        Ok(Some(candidate))
    })))
}

/// `Stream.generate(supplier)`（無限）。
pub fn generate(kind: Kind, supplier: &JObject) -> JResult<JObject> {
    let s = check_fn(supplier)?;
    let method = match kind {
        Kind::Obj => "get",
        Kind::Int => "getAsInt",
        Kind::Long => "getAsLong",
        Kind::Double => "getAsDouble",
    };
    Ok(new_stream(kind, Box::new(move || Ok(Some(s.invoke(method, &[])?)))))
}

/// `Stream.concat(a, b)`。
pub fn concat(a: &JObject, b: &JObject) -> JResult<JObject> {
    let (kind, mut pa) = take(a)?;
    let (_, mut pb) = take(b)?;
    let mut first = true;
    Ok(new_stream(kind, Box::new(move || {
        if first {
            if let Some(x) = pa()? {
                return Ok(Some(x));
            }
            first = false;
        }
        pb()
    })))
}

/// `string.chars()`（UTF-16 の単位の IntStream）。
pub fn chars(s: &JString) -> JResult<JObject> {
    Ok(from_vec(Kind::Int, s.as_str()?.encode_utf16().map(|c| box_i32(c as i32)).collect()))
}

// ------------------------------------------------------------------ 中間操作

/// `filter(predicate)`。
pub fn filter(s: &JObject, p: &JObject) -> JResult<JObject> {
    let p = check_fn(p)?;
    let (kind, mut up) = take(s)?;
    Ok(new_stream(kind, Box::new(move || {
        while let Some(x) = up()? {
            if p.invoke("test", &[x.clone()])?.unbox_bool()? {
                return Ok(Some(x));
            }
        }
        Ok(None)
    })))
}

/// `map(f)` / `mapToInt(f)` / `mapToObj(f)` など。to は結果の種類、method は f のメソッド名。
pub fn map(s: &JObject, f: &JObject, to: Kind, method: &'static str) -> JResult<JObject> {
    let f = check_fn(f)?;
    let (_, mut up) = take(s)?;
    Ok(new_stream(to, Box::new(move || match up()? {
        Some(x) => Ok(Some(f.invoke(method, &[x])?)),
        None => Ok(None),
    })))
}

/// 同じ種類への `map`（Stream.map / IntStream.map など）。
pub fn map_same(s: &JObject, f: &JObject) -> JResult<JObject> {
    let kind = stream_of(s)?.kind;
    map(s, f, kind, kind.unary())
}

/// `boxed()` / `asLongStream()` / `asDoubleStream()`（要素のボックスの型を変える）。
pub fn convert(s: &JObject, to: Kind) -> JResult<JObject> {
    let (_, mut up) = take(s)?;
    Ok(new_stream(to, Box::new(move || match up()? {
        Some(x) => Ok(Some(match to {
            Kind::Long => box_i64(crate::lang::boxed::number_i64(&x)?),
            Kind::Double => box_f64(crate::lang::boxed::number_f64(&x)?),
            _ => x,
        })),
        None => Ok(None),
    })))
}

/// `flatMap(f)`（f はストリームを返す）。
pub fn flat_map(s: &JObject, f: &JObject, to: Kind) -> JResult<JObject> {
    let f = check_fn(f)?;
    let (_, mut up) = take(s)?;
    let mut inner: Option<Pull> = None;
    Ok(new_stream(to, Box::new(move || loop {
        if let Some(p) = inner.as_mut() {
            if let Some(x) = p()? {
                return Ok(Some(x));
            }
            inner = None;
        }
        match up()? {
            Some(x) => {
                let st = f.invoke("apply", &[x])?;
                if !st.is_null() {
                    inner = Some(take(&st)?.1);
                }
            }
            None => return Ok(None),
        }
    })))
}

/// `distinct()`（equals / hashCode で、最初に現れたものを残す）。
pub fn distinct(s: &JObject) -> JResult<JObject> {
    let (kind, mut up) = take(s)?;
    let seen = new_set(SetKind::Hash);
    Ok(new_stream(kind, Box::new(move || {
        while let Some(x) = up()? {
            if seen.add(x.clone())? {
                return Ok(Some(x));
            }
        }
        Ok(None)
    })))
}

/// `sorted()` / `sorted(comparator)`（安定ソート。上流をすべて読んでから並べる）。
pub fn sorted(s: &JObject, cmp: &JObject) -> JResult<JObject> {
    let (kind, up) = take(s)?;
    let cmp = cmp.clone();
    let mut up = Some(up);
    let mut items: std::vec::IntoIter<JObject> = Vec::new().into_iter();
    Ok(new_stream(kind, Box::new(move || {
        if let Some(p) = up.take() {
            let mut v = drain(p)?;
            try_sort_by(&mut v, |a, b| Ok(compare_with(&cmp, a, b)?.cmp(&0)))?;
            items = v.into_iter();
        }
        Ok(items.next())
    })))
}

/// `limit(n)`。
pub fn limit(s: &JObject, n: i64) -> JResult<JObject> {
    if n < 0 {
        return throw("java.lang.IllegalArgumentException", Some(&n.to_string()));
    }
    let (kind, mut up) = take(s)?;
    let mut left = n;
    Ok(new_stream(kind, Box::new(move || {
        if left <= 0 {
            return Ok(None);
        }
        left -= 1;
        up()
    })))
}

/// `skip(n)`。
pub fn skip(s: &JObject, n: i64) -> JResult<JObject> {
    if n < 0 {
        return throw("java.lang.IllegalArgumentException", Some(&n.to_string()));
    }
    let (kind, mut up) = take(s)?;
    let mut to_skip = n;
    Ok(new_stream(kind, Box::new(move || {
        while to_skip > 0 {
            to_skip -= 1;
            if up()?.is_none() {
                return Ok(None);
            }
        }
        up()
    })))
}

/// `peek(action)`。
pub fn peek(s: &JObject, action: &JObject) -> JResult<JObject> {
    let a = check_fn(action)?;
    let (kind, mut up) = take(s)?;
    Ok(new_stream(kind, Box::new(move || match up()? {
        Some(x) => {
            a.invoke("accept", &[x.clone()])?;
            Ok(Some(x))
        }
        None => Ok(None),
    })))
}

/// `takeWhile(predicate)`。
pub fn take_while(s: &JObject, p: &JObject) -> JResult<JObject> {
    let p = check_fn(p)?;
    let (kind, mut up) = take(s)?;
    let mut done = false;
    Ok(new_stream(kind, Box::new(move || {
        if done {
            return Ok(None);
        }
        match up()? {
            Some(x) if p.invoke("test", &[x.clone()])?.unbox_bool()? => Ok(Some(x)),
            _ => {
                done = true;
                Ok(None)
            }
        }
    })))
}

/// `dropWhile(predicate)`。
pub fn drop_while(s: &JObject, p: &JObject) -> JResult<JObject> {
    let p = check_fn(p)?;
    let (kind, mut up) = take(s)?;
    let mut dropping = true;
    Ok(new_stream(kind, Box::new(move || {
        while dropping {
            match up()? {
                Some(x) if p.invoke("test", &[x.clone()])?.unbox_bool()? => {}
                other => {
                    dropping = false;
                    return Ok(other);
                }
            }
        }
        up()
    })))
}

/// `parallel()` / `sequential()` / `unordered()` / `onClose()`（逐次で実行するので何もしない）。
pub fn same(s: &JObject) -> JResult<JObject> {
    stream_of(s)?;
    Ok(s.clone())
}

/// `close()`。
pub fn close(s: &JObject) -> JResult<()> {
    stream_of(s)?.closed.set(true);
    Ok(())
}

// ------------------------------------------------------------------ 終端操作

/// `forEach(action)` / `forEachOrdered(action)`。
pub fn for_each(s: &JObject, action: &JObject) -> JResult<()> {
    let a = check_fn(action)?;
    let (_, mut up) = take(s)?;
    while let Some(x) = up()? {
        a.invoke("accept", &[x])?;
    }
    Ok(())
}

/// `toList()`（変更できないリスト）。
pub fn to_list(s: &JObject) -> JResult<JObject> {
    let (_, up) = take(s)?;
    Ok(crate::util::collections::unmodifiable_from_vec(drain(up)?))
}

/// `toArray()`（Object[]）/ `toArray(generator)`。
pub fn to_array(s: &JObject) -> JResult<JArray<JObject>> {
    let (_, up) = take(s)?;
    Ok(JArray::from_vec(drain(up)?))
}

/// `toArray(String[]::new)` など（要素を指定の型の配列にする）。
pub fn to_typed_array<T: Clone + 'static>(s: &JObject, convert: impl Fn(&JObject) -> JResult<T>) -> JResult<JArray<T>> {
    let (_, up) = take(s)?;
    let mut out = Vec::new();
    for x in drain(up)? {
        out.push(convert(&x)?);
    }
    Ok(JArray::from_vec(out))
}

/// `IntStream.toArray()`。
pub fn to_int_array(s: &JObject) -> JResult<JArray<i32>> {
    to_typed_array(s, |x| x.unbox_i32())
}

/// `LongStream.toArray()`。
pub fn to_long_array(s: &JObject) -> JResult<JArray<i64>> {
    to_typed_array(s, |x| x.unbox_i64())
}

/// `DoubleStream.toArray()`。
pub fn to_double_array(s: &JObject) -> JResult<JArray<f64>> {
    to_typed_array(s, |x| x.unbox_f64())
}

/// `toArray(String[]::new)`。
pub fn to_string_array(s: &JObject) -> JResult<JArray<JString>> {
    to_typed_array(s, |x| x.cast_string())
}

/// `count()`。
pub fn count(s: &JObject) -> JResult<i64> {
    let (_, mut up) = take(s)?;
    let mut n = 0i64;
    while up()?.is_some() {
        n += 1;
    }
    Ok(n)
}

/// `reduce(identity, op)`。
pub fn reduce(s: &JObject, identity: impl Into<JObject>, op: &JObject) -> JResult<JObject> {
    let op = check_fn(op)?;
    let (kind, mut up) = take(s)?;
    let mut acc = identity.into();
    while let Some(x) = up()? {
        acc = op.invoke(kind.unary(), &[acc, x])?;
    }
    Ok(acc)
}

/// `reduce(op)`（Optional / OptionalInt など）。
pub fn reduce_optional(s: &JObject, op: &JObject) -> JResult<JObject> {
    let op = check_fn(op)?;
    let (kind, mut up) = take(s)?;
    let mut acc: Option<JObject> = None;
    while let Some(x) = up()? {
        acc = Some(match acc {
            None => x,
            Some(a) => op.invoke(kind.unary(), &[a, x])?,
        });
    }
    match acc {
        Some(v) => optional::of(kind.opt(), v),
        None => Ok(optional::empty(kind.opt())),
    }
}

/// `sum()`（IntStream: int、LongStream: long、DoubleStream: double。ボクシングして返す）。
pub fn sum(s: &JObject) -> JResult<JObject> {
    let (kind, up) = take(s)?;
    let items = drain(up)?;
    Ok(match kind {
        Kind::Int => {
            let mut t = 0i32;
            for x in &items {
                t = t.wrapping_add(x.unbox_i32()?);
            }
            box_i32(t)
        }
        Kind::Long => {
            let mut t = 0i64;
            for x in &items {
                t = t.wrapping_add(x.unbox_i64()?);
            }
            box_i64(t)
        }
        _ => {
            let mut d = DoubleSum::default();
            for x in &items {
                d.add(x.unbox_f64()?);
            }
            box_f64(d.sum())
        }
    })
}

/// `IntStream.sum()`。
pub fn sum_int(s: &JObject) -> JResult<i32> {
    sum(s)?.unbox_i32()
}

/// `LongStream.sum()`。
pub fn sum_long(s: &JObject) -> JResult<i64> {
    sum(s)?.unbox_i64()
}

/// `DoubleStream.sum()`。
pub fn sum_double(s: &JObject) -> JResult<f64> {
    sum(s)?.unbox_f64()
}

/// Java の DoubleStream / DoubleSummaryStatistics と同じ補正付きの合計（Kahan）。
#[derive(Default)]
struct DoubleSum {
    sum: f64,
    compensation: f64,
    simple: f64,
}

impl DoubleSum {
    fn add(&mut self, v: f64) {
        self.simple += v;
        let tmp = v - self.compensation;
        let velvel = self.sum + tmp;
        self.compensation = (velvel - self.sum) - tmp;
        self.sum = velvel;
    }

    fn sum(&self) -> f64 {
        let tmp = self.sum - self.compensation;
        if tmp.is_nan() && self.simple.is_infinite() {
            self.simple
        } else {
            tmp
        }
    }
}

/// `average()`（OptionalDouble）。
pub fn average(s: &JObject) -> JResult<JObject> {
    let (kind, up) = take(s)?;
    let items = drain(up)?;
    if items.is_empty() {
        return Ok(optional::empty(OptKind::Double));
    }
    let avg = match kind {
        Kind::Int | Kind::Long => {
            let mut t = 0i64;
            for x in &items {
                t = t.wrapping_add(crate::lang::boxed::number_i64(x)?);
            }
            t as f64 / items.len() as f64
        }
        _ => {
            let mut d = DoubleSum::default();
            for x in &items {
                d.add(x.unbox_f64()?);
            }
            d.sum() / items.len() as f64
        }
    };
    optional::of(OptKind::Double, box_f64(avg))
}

fn extreme(s: &JObject, cmp: &JObject, max: bool) -> JResult<JObject> {
    let (kind, mut up) = take(s)?;
    let mut best: Option<JObject> = None;
    while let Some(x) = up()? {
        best = Some(match best {
            None => x,
            Some(b) => {
                let c = if kind == Kind::Double && cmp.is_null() {
                    // DoubleStream.max / min は Math.max / min（NaN が勝つ）。
                    let (bv, xv) = (b.unbox_f64()?, x.unbox_f64()?);
                    let r = if max { crate::lang::math::max_f64(bv, xv) } else { crate::lang::math::min_f64(bv, xv) };
                    if r.to_bits() == bv.to_bits() { 1 } else { -1 }
                } else {
                    compare_with(cmp, &b, &x)?
                };
                // maxBy: compare(a, b) >= 0 ? a : b、minBy: compare(a, b) <= 0 ? a : b
                if (max && c >= 0) || (!max && c <= 0) { b } else { x }
            }
        });
    }
    match best {
        Some(v) => optional::of(kind.opt(), v),
        None => Ok(optional::empty(kind.opt())),
    }
}

/// `max(comparator)` / `IntStream.max()`（comparator が null なら自然順序）。
pub fn max(s: &JObject, cmp: &JObject) -> JResult<JObject> {
    extreme(s, cmp, true)
}

/// `min(comparator)` / `IntStream.min()`。
pub fn min(s: &JObject, cmp: &JObject) -> JResult<JObject> {
    extreme(s, cmp, false)
}

fn match_any(s: &JObject, p: &JObject, want: bool) -> JResult<bool> {
    let p = check_fn(p)?;
    let (_, mut up) = take(s)?;
    while let Some(x) = up()? {
        if p.invoke("test", &[x])?.unbox_bool()? == want {
            return Ok(true);
        }
    }
    Ok(false)
}

/// `anyMatch(p)`。
pub fn any_match(s: &JObject, p: &JObject) -> JResult<bool> {
    match_any(s, p, true)
}

/// `allMatch(p)`。
pub fn all_match(s: &JObject, p: &JObject) -> JResult<bool> {
    Ok(!match_any(s, p, false)?)
}

/// `noneMatch(p)`。
pub fn none_match(s: &JObject, p: &JObject) -> JResult<bool> {
    Ok(!match_any(s, p, true)?)
}

/// `findFirst()` / `findAny()`（要素が null なら NullPointerException）。
pub fn find_first(s: &JObject) -> JResult<JObject> {
    let (kind, mut up) = take(s)?;
    match up()? {
        Some(x) if x.is_null() => npe(),
        Some(x) => optional::of(kind.opt(), x),
        None => Ok(optional::empty(kind.opt())),
    }
}

/// `iterator()`。
pub fn iterator(s: &JObject) -> JResult<JObject> {
    let (_, up) = take(s)?;
    let list = crate::util::collections::unmodifiable_from_vec(drain(up)?);
    list.iterator()
}

/// `summaryStatistics()`（IntSummaryStatistics など）。
pub fn summary_statistics(s: &JObject) -> JResult<JObject> {
    let (kind, up) = take(s)?;
    let st = new_statistics(kind);
    for x in drain(up)? {
        statistics_accept(&st, &x)?;
    }
    Ok(st)
}

// ------------------------------------------------------------------ IntSummaryStatistics など

pub struct JStatistics {
    base: ObjectBase,
    kind: Kind,
    count: Cell<i64>,
    isum: Cell<i64>,
    imin: Cell<i64>,
    imax: Cell<i64>,
    dsum: RefCell<DoubleSum>,
    dmin: Cell<f64>,
    dmax: Cell<f64>,
}

impl Object for JStatistics {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        match self.kind {
            Kind::Int | Kind::Obj => "java.util.IntSummaryStatistics",
            Kind::Long => "java.util.LongSummaryStatistics",
            Kind::Double => "java.util.DoubleSummaryStatistics",
        }
    }
    fn to_jstring(&self) -> JResult<JString> {
        let name = &self.class_name()["java.util.".len()..];
        let avg = crate::lang::format::format_str("%f", &[box_f64(statistics_average_of(self))])?;
        Ok(JString::from(match self.kind {
            Kind::Double => {
                let f = |v: f64| crate::lang::format::format_str("%f", &[box_f64(v)]);
                format!("{name}{{count={}, sum={}, min={}, average={avg}, max={}}}", self.count.get(), f(self.dsum.borrow().sum())?,
                        f(self.dmin.get())?, f(self.dmax.get())?)
            }
            _ => format!("{name}{{count={}, sum={}, min={}, average={avg}, max={}}}", self.count.get(), self.isum.get(),
                         self.imin.get(), self.imax.get()),
        }))
    }
}

fn new_statistics(kind: Kind) -> JObject {
    let (imin, imax) = match kind {
        Kind::Long => (i64::MAX, i64::MIN),
        _ => (i32::MAX as i64, i32::MIN as i64),
    };
    alloc(|base| JStatistics {
        base,
        kind,
        count: Cell::new(0),
        isum: Cell::new(0),
        imin: Cell::new(imin),
        imax: Cell::new(imax),
        dsum: RefCell::new(DoubleSum::default()),
        dmin: Cell::new(f64::INFINITY),
        dmax: Cell::new(f64::NEG_INFINITY),
    })
}

fn stats(o: &JObject) -> JResult<&JStatistics> {
    match o.downcast_ref::<JStatistics>() {
        Some(s) => Ok(s),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.IntSummaryStatistics")
        }
    }
}

fn statistics_accept(o: &JObject, x: &JObject) -> JResult<()> {
    let s = stats(o)?;
    s.count.set(s.count.get() + 1);
    if s.kind == Kind::Double {
        let v = crate::lang::boxed::number_f64(x)?;
        s.dsum.borrow_mut().add(v);
        s.dmin.set(crate::lang::math::min_f64(s.dmin.get(), v));
        s.dmax.set(crate::lang::math::max_f64(s.dmax.get(), v));
    } else {
        let v = crate::lang::boxed::number_i64(x)?;
        s.isum.set(s.isum.get().wrapping_add(v));
        s.imin.set(s.imin.get().min(v));
        s.imax.set(s.imax.get().max(v));
    }
    Ok(())
}

fn statistics_average_of(s: &JStatistics) -> f64 {
    if s.count.get() == 0 {
        return 0.0;
    }
    if s.kind == Kind::Double {
        s.dsum.borrow().sum() / s.count.get() as f64
    } else {
        s.isum.get() as f64 / s.count.get() as f64
    }
}

/// `getCount()`。
pub fn statistics_count(o: &JObject) -> JResult<i64> {
    Ok(stats(o)?.count.get())
}

/// `getSum()`（Int / Long は long、Double は double。ボクシングして返す）。
pub fn statistics_sum(o: &JObject) -> JResult<JObject> {
    let s = stats(o)?;
    Ok(if s.kind == Kind::Double { box_f64(s.dsum.borrow().sum()) } else { box_i64(s.isum.get()) })
}

/// `getMin()`。
pub fn statistics_min(o: &JObject) -> JResult<JObject> {
    let s = stats(o)?;
    Ok(match s.kind {
        Kind::Double => box_f64(s.dmin.get()),
        Kind::Long => box_i64(s.imin.get()),
        _ => box_i32(s.imin.get() as i32),
    })
}

/// `getMax()`。
pub fn statistics_max(o: &JObject) -> JResult<JObject> {
    let s = stats(o)?;
    Ok(match s.kind {
        Kind::Double => box_f64(s.dmax.get()),
        Kind::Long => box_i64(s.imax.get()),
        _ => box_i32(s.imax.get() as i32),
    })
}

/// `getAverage()`。
pub fn statistics_average(o: &JObject) -> JResult<f64> {
    Ok(statistics_average_of(stats(o)?))
}

// ------------------------------------------------------------------ Collectors

enum CKind {
    ToList,
    ToUnmodifiableList,
    ToSet,
    ToMap { key: JObject, value: JObject, merge: JObject, supplier: JObject },
    Joining { sep: JString, prefix: JString, suffix: JString },
    Grouping { classifier: JObject, supplier: JObject, downstream: JObject },
    Partitioning { predicate: JObject, downstream: JObject },
    Counting,
    Summing(Kind, JObject),
    Averaging(Kind, JObject),
    Summarizing(Kind, JObject),
    Mapping { f: JObject, downstream: JObject },
    Filtering { p: JObject, downstream: JObject },
    FlatMapping { f: JObject, downstream: JObject },
    ToCollection(JObject),
    MinBy(JObject),
    MaxBy(JObject),
    Reducing { identity: Option<JObject>, op: JObject },
    AndThen { downstream: JObject, finisher: JObject },
}

pub struct JCollector {
    base: ObjectBase,
    kind: CKind,
}

impl Object for JCollector {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.stream.Collectors$CollectorImpl"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.stream.Collector")
    }
}

fn collector(kind: CKind) -> JObject {
    alloc(|base| JCollector { base, kind })
}

pub fn to_list_collector() -> JObject {
    collector(CKind::ToList)
}
pub fn to_unmodifiable_list_collector() -> JObject {
    collector(CKind::ToUnmodifiableList)
}
pub fn to_set_collector() -> JObject {
    collector(CKind::ToSet)
}
/// `Collectors.toMap(k, v)` / `toMap(k, v, merge)` / `toMap(k, v, merge, supplier)`（merge / supplier は null 可）。
pub fn to_map_collector(key: &JObject, value: &JObject, merge: &JObject, supplier: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::ToMap { key: check_fn(key)?, value: check_fn(value)?, merge: merge.clone(), supplier: supplier.clone() }))
}
/// `Collectors.joining(sep, prefix, suffix)`。
pub fn joining_collector(sep: &JString, prefix: &JString, suffix: &JString) -> JResult<JObject> {
    sep.as_str()?;
    prefix.as_str()?;
    suffix.as_str()?;
    Ok(collector(CKind::Joining { sep: sep.clone(), prefix: prefix.clone(), suffix: suffix.clone() }))
}
/// `Collectors.groupingBy(f)` / `groupingBy(f, downstream)` / `groupingBy(f, supplier, downstream)`
/// （supplier / downstream は null 可: HashMap / toList）。
pub fn grouping_collector(classifier: &JObject, supplier: &JObject, downstream: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Grouping { classifier: check_fn(classifier)?, supplier: supplier.clone(), downstream: downstream.clone() }))
}
/// `Collectors.partitioningBy(p)` / `partitioningBy(p, downstream)`。
pub fn partitioning_collector(predicate: &JObject, downstream: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Partitioning { predicate: check_fn(predicate)?, downstream: downstream.clone() }))
}
pub fn counting_collector() -> JObject {
    collector(CKind::Counting)
}
/// `Collectors.summingInt(f)` など。
pub fn summing_collector(kind: Kind, f: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Summing(kind, check_fn(f)?)))
}
/// `Collectors.averagingInt(f)` など。
pub fn averaging_collector(kind: Kind, f: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Averaging(kind, check_fn(f)?)))
}
/// `Collectors.summarizingInt(f)` など。
pub fn summarizing_collector(kind: Kind, f: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Summarizing(kind, check_fn(f)?)))
}
pub fn mapping_collector(f: &JObject, downstream: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Mapping { f: check_fn(f)?, downstream: check_fn(downstream)? }))
}
pub fn filtering_collector(p: &JObject, downstream: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Filtering { p: check_fn(p)?, downstream: check_fn(downstream)? }))
}
pub fn flat_mapping_collector(f: &JObject, downstream: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::FlatMapping { f: check_fn(f)?, downstream: check_fn(downstream)? }))
}
pub fn to_collection_collector(supplier: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::ToCollection(check_fn(supplier)?)))
}
pub fn min_by_collector(cmp: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::MinBy(check_fn(cmp)?)))
}
pub fn max_by_collector(cmp: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::MaxBy(check_fn(cmp)?)))
}
/// `Collectors.reducing(identity, op)` / `reducing(op)`（identity が None なら Optional を返す）。
pub fn reducing_collector(identity: Option<JObject>, op: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::Reducing { identity, op: check_fn(op)? }))
}
pub fn collecting_and_then(downstream: &JObject, finisher: &JObject) -> JResult<JObject> {
    Ok(collector(CKind::AndThen { downstream: check_fn(downstream)?, finisher: check_fn(finisher)? }))
}

fn number_method(kind: Kind) -> &'static str {
    match kind {
        Kind::Long => "applyAsLong",
        Kind::Double => "applyAsDouble",
        _ => "applyAsInt",
    }
}

/// `stream.collect(collector)`。
pub fn collect(s: &JObject, c: &JObject) -> JResult<JObject> {
    let (_, up) = take(s)?;
    let items = drain(up)?;
    collect_items(c, items)
}

fn collect_items(c: &JObject, items: Vec<JObject>) -> JResult<JObject> {
    let col = match c.downcast_ref::<JCollector>() {
        Some(x) => x,
        None => {
            c.obj()?;
            return throw("java.lang.UnsupportedOperationException", Some("user-defined Collector"));
        }
    };
    match &col.kind {
        CKind::ToList => {
            let l = new_list(ListKind::ArrayList);
            for x in items {
                l.add(x)?;
            }
            Ok(l)
        }
        CKind::ToUnmodifiableList => crate::util::collections::list_from_vec(ListKind::Immutable, items),
        CKind::ToSet => {
            let set = new_set(SetKind::Hash);
            for x in items {
                set.add(x)?;
            }
            Ok(set)
        }
        CKind::ToMap { key, value, merge, supplier } => {
            let map = if supplier.is_null() { new_map(MapKind::Hash) } else { supplier.invoke("get", &[])? };
            for x in items {
                let k = key.invoke("apply", &[x.clone()])?;
                let v = value.invoke("apply", &[x])?;
                if v.is_null() {
                    return npe();
                }
                if merge.is_null() {
                    let old = map.put_if_absent(k.clone(), v.clone())?;
                    if !old.is_null() {
                        let msg = format!("Duplicate key {} (attempted merging values {} and {})",
                                          crate::lang::stringify::to_java_string(&k)?, crate::lang::stringify::to_java_string(&old)?,
                                          crate::lang::stringify::to_java_string(&v)?);
                        return throw("java.lang.IllegalStateException", Some(&msg));
                    }
                } else {
                    map.merge(k, v, merge)?;
                }
            }
            Ok(map)
        }
        CKind::Joining { sep, prefix, suffix } => {
            let mut out = String::from(prefix.as_str()?);
            for (i, x) in items.iter().enumerate() {
                if i > 0 {
                    out.push_str(sep.as_str()?);
                }
                out.push_str(&crate::lang::stringify::to_java_string(x)?);
            }
            out.push_str(suffix.as_str()?);
            Ok(JObject::from(JString::from(out)))
        }
        CKind::Grouping { classifier, supplier, downstream } => {
            let map = if supplier.is_null() { new_map(MapKind::Hash) } else { supplier.invoke("get", &[])? };
            let mut groups: Vec<Vec<JObject>> = Vec::new();
            let index = new_map(MapKind::Hash);
            for x in items {
                let k = classifier.invoke("apply", &[x.clone()])?;
                if k.is_null() {
                    return throw("java.lang.NullPointerException", Some("element cannot be mapped to a null key"));
                }
                let found = index.map_get(k.clone())?;
                let i = if found.is_null() {
                    // Java の groupingBy は computeIfAbsent で入れる（反復順もそれに従う）。
                    let i = groups.len();
                    groups.push(Vec::new());
                    index.put(k.clone(), box_i32(i as i32))?;
                    let slot = box_i32(i as i32);
                    map.compute_if_absent(k, &crate::lambda::lambda(&["java.util.function.Function"], move |_| Ok(slot.clone())))?;
                    i
                } else {
                    found.unbox_i32()? as usize
                };
                groups[i].push(x);
            }
            let down = if downstream.is_null() { to_list_collector() } else { downstream.clone() };
            for e in crate::util::collections::to_vec(&map.entry_set()?)? {
                let i = e.get_value()?.unbox_i32()? as usize;
                let v = collect_items(&down, std::mem::take(&mut groups[i]))?;
                e.set_value(v)?;
            }
            Ok(map)
        }
        CKind::Partitioning { predicate, downstream } => {
            let (mut yes, mut no) = (Vec::new(), Vec::new());
            for x in items {
                if predicate.invoke("test", &[x.clone()])?.unbox_bool()? {
                    yes.push(x);
                } else {
                    no.push(x);
                }
            }
            let down = if downstream.is_null() { to_list_collector() } else { downstream.clone() };
            let map = new_map(MapKind::LinkedHash);
            map.put(box_bool(false), collect_items(&down, no)?)?;
            map.put(box_bool(true), collect_items(&down, yes)?)?;
            Ok(map)
        }
        CKind::Counting => Ok(box_i64(items.len() as i64)),
        CKind::Summing(kind, f) => {
            let st = new_statistics(*kind);
            for x in items {
                statistics_accept(&st, &f.invoke(number_method(*kind), &[x])?)?;
            }
            let sum = statistics_sum(&st)?;
            Ok(if *kind == Kind::Int { box_i32(sum.unbox_i64()? as i32) } else { sum })
        }
        CKind::Averaging(kind, f) => {
            let st = new_statistics(*kind);
            for x in items {
                statistics_accept(&st, &f.invoke(number_method(*kind), &[x])?)?;
            }
            Ok(box_f64(statistics_average(&st)?))
        }
        CKind::Summarizing(kind, f) => {
            let st = new_statistics(*kind);
            for x in items {
                statistics_accept(&st, &f.invoke(number_method(*kind), &[x])?)?;
            }
            Ok(st)
        }
        CKind::Mapping { f, downstream } => {
            let mut mapped = Vec::with_capacity(items.len());
            for x in items {
                mapped.push(f.invoke("apply", &[x])?);
            }
            collect_items(downstream, mapped)
        }
        CKind::Filtering { p, downstream } => {
            let mut kept = Vec::new();
            for x in items {
                if p.invoke("test", &[x.clone()])?.unbox_bool()? {
                    kept.push(x);
                }
            }
            collect_items(downstream, kept)
        }
        CKind::FlatMapping { f, downstream } => {
            let mut all = Vec::new();
            for x in items {
                let st = f.invoke("apply", &[x])?;
                if !st.is_null() {
                    all.extend(drain(take(&st)?.1)?);
                }
            }
            collect_items(downstream, all)
        }
        CKind::ToCollection(supplier) => {
            let c = supplier.invoke("get", &[])?;
            for x in items {
                c.add(x)?;
            }
            Ok(c)
        }
        CKind::MinBy(cmp) | CKind::MaxBy(cmp) => {
            let is_max = matches!(col.kind, CKind::MaxBy(_));
            let st = from_vec(Kind::Obj, items);
            extreme(&st, cmp, is_max)
        }
        CKind::Reducing { identity, op } => {
            let mut acc = identity.clone();
            for x in items {
                acc = Some(match acc {
                    None => x,
                    Some(a) => op.invoke("apply", &[a, x])?,
                });
            }
            match (identity, acc) {
                (Some(_), Some(v)) => Ok(v),
                (None, Some(v)) => optional::of(OptKind::Obj, v),
                (_, None) => Ok(optional::empty(OptKind::Obj)),
            }
        }
        CKind::AndThen { downstream, finisher } => {
            let r = collect_items(downstream, items)?;
            finisher.invoke("apply", &[r])
        }
    }
}
