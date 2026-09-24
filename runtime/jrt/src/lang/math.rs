//! java.lang.Math のうち、Rust の標準メソッドと意味が異なるもの。

/// `Math.max(double, double)`: NaN を含めば NaN、`max(-0.0, 0.0) == 0.0`。
pub fn max_f64(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        return f64::NAN;
    }
    if a == 0.0 && b == 0.0 {
        return if a.is_sign_negative() { b } else { a };
    }
    if a >= b { a } else { b }
}

/// `Math.min(double, double)`。
pub fn min_f64(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        return f64::NAN;
    }
    if a == 0.0 && b == 0.0 {
        return if a.is_sign_negative() { a } else { b };
    }
    if a <= b { a } else { b }
}

/// `Math.round(double)`: 正の無限大方向への四捨五入。NaN は 0、範囲外は飽和。
pub fn round_f64(a: f64) -> i64 {
    if a.is_nan() {
        return 0;
    }
    let f = a.floor();
    let r = if a - f >= 0.5 { f + 1.0 } else { f };
    r as i64
}

/// `Math.round(float)`。
pub fn round_f32(a: f32) -> i32 {
    if a.is_nan() {
        return 0;
    }
    let f = a.floor();
    let r = if a - f >= 0.5 { f + 1.0 } else { f };
    r as i32
}

/// `Math.floorDiv(int, int)`。
pub fn floor_div_i32(a: i32, b: i32) -> crate::JResult<i32> {
    let q = crate::num::div_i32(a, b)?;
    Ok(if a.wrapping_rem(b) != 0 && (a ^ b) < 0 { q.wrapping_sub(1) } else { q })
}

/// `Math.floorMod(int, int)`。
pub fn floor_mod_i32(a: i32, b: i32) -> crate::JResult<i32> {
    let m = crate::num::rem_i32(a, b)?;
    Ok(if m != 0 && (m ^ b) < 0 { m.wrapping_add(b) } else { m })
}

/// `Math.floorDiv(long, long)`。
pub fn floor_div_i64(a: i64, b: i64) -> crate::JResult<i64> {
    let q = crate::num::div_i64(a, b)?;
    Ok(if a.wrapping_rem(b) != 0 && (a ^ b) < 0 { q.wrapping_sub(1) } else { q })
}

/// `Math.floorMod(long, long)`。
pub fn floor_mod_i64(a: i64, b: i64) -> crate::JResult<i64> {
    let m = crate::num::rem_i64(a, b)?;
    Ok(if m != 0 && (m ^ b) < 0 { m.wrapping_add(b) } else { m })
}

/// `Math.signum(double)`。
pub fn signum_f64(a: f64) -> f64 {
    if a.is_nan() || a == 0.0 { a } else { a.signum() }
}
