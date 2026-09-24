//! オブジェクト・例外・コレクション・書式の確認（期待値は JDK 21 の実際の出力）。

use jrt::prelude::*;
use jrt::util::collections::{new_map, new_priority_queue_from, new_set, MapKind, SetKind};
use jrt::util::random;
use jrt::{box_i32, Flow};

fn s(x: &str) -> JObject {
    JObject::from(JString::from(x))
}

#[test]
fn random_matches_java() -> JResult<()> {
    let r = random::new_random(42);
    assert_eq!(random::next_int(&r)?, -1170105035);
    assert_eq!(random::next_int_bound(&r, 100)?, 63);
    assert_eq!(random::next_long(&r)?, -5843495416241995736);
    assert_eq!(random::next_double(&r)?, 0.30871945533265976);
    assert!(!random::next_boolean(&r)?);
    assert_eq!(random::next_int_bound(&r, 64)?, 45);
    assert!(random::next_int_bound(&r, 0).unwrap_err().instance_of("java.lang.IllegalArgumentException"));
    Ok(())
}

#[test]
fn hash_map_iteration_order_matches_java() -> JResult<()> {
    let m = new_map(MapKind::Hash);
    let sum = jrt::lambda(&["java.util.function.BiFunction"], |a| Ok(box_i32(a[0].unbox_i32()? + a[1].unbox_i32()?)));
    for w in "the quick brown fox jumps over the lazy dog again and again".split(' ') {
        m.merge(s(w), box_i32(1), &sum)?;
    }
    assert_eq!(
        m.to_jstring()?.as_str()?,
        "{over=1, the=2, quick=1, and=1, lazy=1, again=2, jumps=1, brown=1, dog=1, fox=1}"
    );
    let hs = new_set(SetKind::Hash);
    for i in 0..20 {
        hs.add(box_i32(i * 37 % 101 - 50))?;
    }
    assert_eq!(
        hs.to_jstring()?.as_str()?,
        "[0, -3, 34, -37, 37, -40, 7, -10, 10, -13, 44, -47, 47, -50, 17, -20, 24, -27, 27, -30]"
    );
    Ok(())
}

#[test]
fn priority_queue_layout_matches_java() -> JResult<()> {
    let src = jrt::util::misc::list_of(&JArray::from_vec([5, 3, 8, 1, 9, 2].iter().map(|&x| box_i32(x)).collect()))?;
    let pq = new_priority_queue_from(&src)?;
    assert_eq!(pq.to_jstring()?.as_str()?, "[1, 3, 2, 5, 9, 8]");
    pq.poll()?;
    pq.add(box_i32(4))?;
    assert_eq!(pq.to_jstring()?.as_str()?, "[2, 3, 4, 5, 9, 8]");
    Ok(())
}

#[test]
fn format_matches_java() -> JResult<()> {
    let args: Vec<JObject> = vec![
        box_i32(42), box_i32(42), box_i32(42), box_i32(1234567), box_i32(255), box_i32(-1), box_i32(8),
        jrt::box_f64(1.005), jrt::box_f64(std::f64::consts::PI), jrt::box_f64(12345.678), jrt::box_f64(0.000123),
        s("hi"), s("hi"), s("hi"), jrt::box_u16(b'x' as u16), jrt::box_bool(true),
    ];
    let out = jrt::lang::format::format(
        &jstr!("%5d|%-5d|%05d|%,d|%x|%X|%o|%.2f|%10.3f|%e|%.3e|%s|%10s|%-10s|%c|%b|%%|%n"),
        &JArray::from_vec(args),
    )?;
    assert_eq!(out.as_str()?, "   42|42   |00042|1,234,567|ff|FFFFFFFF|10|1.01|     3.142|1.234568e+04|1.230e-04|hi|        hi|hi        |x|true|%|\n");
    let out = jrt::lang::format::format(
        &jstr!("%.1f %.0f %.2f %+d %(d % d"),
        &JArray::from_vec(vec![jrt::box_f64(0.05), jrt::box_f64(2.5), jrt::box_f64(0.125), box_i32(5), box_i32(-5), box_i32(5)]),
    )?;
    assert_eq!(out.as_str()?, "0.1 3 0.13 +5 (5)  5");
    let e = jrt::lang::format::format(&jstr!("%d"), &JArray::from_vec(vec![s("x")])).unwrap_err();
    assert!(e.instance_of("java.lang.IllegalArgumentException"));
    Ok(())
}

#[test]
fn split_matches_java() -> JResult<()> {
    let show = |a: JArray<JString>| -> JResult<Vec<String>> { Ok(a.to_vec()?.iter().map(|x| x.to_string()).collect()) };
    assert_eq!(show(jstr!("a,b,,c,,").split(&jstr!(","))?)?, ["a", "b", "", "c"]);
    assert_eq!(show(jstr!(" a  b ").split(&jstr!("\\s+"))?)?, ["", "a", "b"]);
    assert_eq!(show(jstr!("abc").split(&jstr!(""))?)?, ["a", "b", "c"]);
    Ok(())
}

#[test]
fn exceptions_are_caught_by_try_block() -> JResult<()> {
    let r = jrt::try_block(|| -> JResult<Flow<()>> {
        jrt::num::div_i32(1, 0)?;
        Ok(Flow::Normal)
    });
    let e = r.err().expect("should throw");
    assert!(e.instance_of("java.lang.ArithmeticException"));
    assert!(e.instance_of("java.lang.RuntimeException"));
    assert!(!e.instance_of("java.lang.Error"));
    assert_eq!(e.to_jstring()?.as_str()?, "java.lang.ArithmeticException: / by zero");

    let r = jrt::try_block(|| -> JResult<Flow<i32>> { Ok(Flow::Return(5)) });
    assert!(matches!(r, Ok(Flow::Return(5))));
    Ok(())
}

#[test]
fn boxing_cache_matches_java_identity() -> JResult<()> {
    assert!(box_i32(127).same(&box_i32(127)));
    assert!(!box_i32(128).same(&box_i32(128)));
    assert!(box_i32(128).equals(&box_i32(128))?);
    assert_eq!(jrt::box_f64(0.5).to_jstring()?.as_str()?, "0.5");
    assert!(JObject::null().is_null());
    let n: JString = JString::null();
    assert_eq!(jconcat!("x", n).as_str()?, "xnull");
    assert!(JObject::null().unbox_i32().unwrap_err().instance_of("java.lang.NullPointerException"));
    assert!(s("x").unbox_i32().unwrap_err().instance_of("java.lang.ClassCastException"));
    Ok(())
}

#[test]
fn null_dereference_throws_npe() {
    assert!(JString::null().length().unwrap_err().instance_of("java.lang.NullPointerException"));
    assert!(JObject::null().to_jstring().unwrap_err().instance_of("java.lang.NullPointerException"));
    assert!(JObject::null().size().unwrap_err().instance_of("java.lang.NullPointerException"));
}

#[test]
fn user_exceptions_propagate_through_collections() -> JResult<()> {
    // 比較関数が送出した例外は、ソートの呼び出し元にそのまま伝わる。
    let list = jrt::util::collections::new_list(jrt::util::collections::ListKind::ArrayList);
    list.add(box_i32(2))?;
    list.add(box_i32(1))?;
    let bad = jrt::lambda(&["java.util.Comparator"], |_| jrt::throw("java.lang.IllegalStateException", Some("boom")));
    let e = list.sort(&bad).unwrap_err();
    assert_eq!(e.to_jstring()?.as_str()?, "java.lang.IllegalStateException: boom");
    assert_eq!(list.to_jstring()?.as_str()?, "[2, 1]");
    Ok(())
}
