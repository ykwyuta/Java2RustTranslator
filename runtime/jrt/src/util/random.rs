//! java.util.Random（Java と同じ線形合同法なので、同じシードなら同じ乱数列になる）。

use crate::object::{alloc, JObject, Object, ObjectBase};
use crate::rt::throw;
use std::cell::Cell;

const MULTIPLIER: i64 = 0x5DEECE66D;
const ADDEND: i64 = 0xB;
const MASK: i64 = (1 << 48) - 1;

pub struct JRandom {
    base: ObjectBase,
    seed: Cell<i64>,
}

impl Object for JRandom {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.Random"
    }
}

thread_local! {
    static SEED_UNIQUIFIER: Cell<i64> = const { Cell::new(8682522807148012) };
    static MATH_RANDOM: JObject = new_random_unseeded();
}

/// `new Random(seed)`。
pub fn new_random(seed: i64) -> JObject {
    alloc(|base| JRandom { base, seed: Cell::new((seed ^ MULTIPLIER) & MASK) })
}

/// `new Random()`（シードは時刻から決める）。
pub fn new_random_unseeded() -> JObject {
    let u = SEED_UNIQUIFIER.with(|s| {
        let v = s.get().wrapping_mul(1181783497276652981);
        s.set(v);
        v
    });
    let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_nanos() as i64);
    new_random(u ^ nanos)
}

fn rnd(o: &JObject) -> &JRandom {
    match o.downcast_ref::<JRandom>() {
        Some(r) => r,
        None => crate::object::bad_cast(o, "java.util.Random"),
    }
}

fn next(r: &JRandom, bits: u32) -> i32 {
    let s = (r.seed.get().wrapping_mul(MULTIPLIER).wrapping_add(ADDEND)) & MASK;
    r.seed.set(s);
    (s >> (48 - bits)) as i32
}

/// `setSeed(seed)`。
pub fn set_seed(o: &JObject, seed: i64) {
    rnd(o).seed.set((seed ^ MULTIPLIER) & MASK);
}

/// `nextInt()`。
pub fn next_int(o: &JObject) -> i32 {
    next(rnd(o), 32)
}

/// `nextInt(bound)`。
pub fn next_int_bound(o: &JObject, bound: i32) -> i32 {
    if bound <= 0 {
        throw("java.lang.IllegalArgumentException", Some("bound must be positive"));
    }
    let r = rnd(o);
    let mut v = next(r, 31);
    let m = bound - 1;
    if bound & m == 0 {
        return ((bound as i64 * v as i64) >> 31) as i32;
    }
    let mut u = v;
    loop {
        v = u % bound;
        if u.wrapping_sub(v).wrapping_add(m) >= 0 {
            return v;
        }
        u = next(r, 31);
    }
}

/// `nextInt(origin, bound)`。
pub fn next_int_range(o: &JObject, origin: i32, bound: i32) -> i32 {
    if origin >= bound {
        throw("java.lang.IllegalArgumentException", Some("bound must be greater than origin"));
    }
    let n = bound.wrapping_sub(origin);
    if n > 0 {
        origin + next_int_bound(o, n)
    } else {
        loop {
            let r = next_int(o);
            if r >= origin && r < bound {
                return r;
            }
        }
    }
}

/// `nextLong()`。
pub fn next_long(o: &JObject) -> i64 {
    let r = rnd(o);
    ((next(r, 32) as i64) << 32).wrapping_add(next(r, 32) as i64)
}

/// `nextBoolean()`。
pub fn next_boolean(o: &JObject) -> bool {
    next(rnd(o), 1) != 0
}

/// `nextDouble()`。
pub fn next_double(o: &JObject) -> f64 {
    let r = rnd(o);
    (((next(r, 26) as i64) << 27) + next(r, 27) as i64) as f64 * (1.0 / (1u64 << 53) as f64)
}

/// `Math.random()`。
pub fn math_random() -> f64 {
    MATH_RANDOM.with(next_double)
}
