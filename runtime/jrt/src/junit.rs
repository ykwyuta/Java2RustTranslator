//! JUnit 5（Jupiter）の Assertions / Assumptions と、変換したテストの実行（`#[test]` 関数から呼ぶ）。
//! 失敗のメッセージは JUnit と同じ書式（`message ==> expected: <1> but was: <2>`）で、
//! org.opentest4j.AssertionFailedError を送出する。

use crate::array::JArray;
use crate::lang::boxed::{box_bool, box_f32, box_f64, box_i16, box_i32, box_i64, box_i8, box_u16};
use crate::lang::string::JString;
use crate::lang::throwable::{describe_chain, new_throwable};
use crate::object::{equals_nullable, JObject};
use crate::rt::JResult;

const FAILED: &str = "org.opentest4j.AssertionFailedError";

/// テストを実行する（main と同じく大きなスタックのスレッドで）。例外で終われば、その内容でパニックする（テストの失敗）。
pub fn run_test(name: &'static str, f: impl FnOnce() -> JResult<()> + Send + 'static) {
    let handle = std::thread::Builder::new()
        .name(name.to_string())
        .stack_size(1 << 30)
        .spawn(move || {
            let r = f();
            crate::lang::thread::finish();
            crate::io::flush_stdout();
            match r {
                Ok(()) => None,
                // Assumptions の失敗はテストの中断（JUnit では skipped）。
                Err(e) if e.instance_of("org.opentest4j.TestAbortedException") => {
                    eprintln!("{name}: aborted: {}", describe_chain(&e));
                    None
                }
                Err(e) => Some(describe_chain(&e)),
            }
        })
        .expect("failed to start the test thread");
    match handle.join() {
        Ok(None) => {}
        Ok(Some(failure)) => panic!("{name} failed: {failure}"),
        Err(p) => std::panic::resume_unwind(p),
    }
}

/// `@BeforeEach` とテスト（first）の後に `@AfterEach`（after）を実行した結果: 先の例外を優先する。
pub fn with_after(first: JResult<()>, after: JResult<()>) -> JResult<()> {
    first.and(after)
}

/// 失敗メッセージ（String、`Supplier<String>`、null）。
fn message(msg: &JObject) -> JResult<Option<String>> {
    if msg.is_null() {
        return Ok(None);
    }
    if let Ok(s) = msg.cast_string() {
        return Ok(Some(s.as_str()?.to_string()));
    }
    let s = msg.invoke("get", &[])?;
    if s.is_null() {
        return Ok(None);
    }
    Ok(Some(crate::lang::stringify::to_java_string(&s)?))
}

fn prefix(msg: &JObject) -> JResult<String> {
    Ok(match message(msg)? {
        Some(m) if !m.trim().is_empty() => format!("{m} ==> "),
        _ => String::new(),
    })
}

fn fail_with<T>(text: String, cause: JObject) -> JResult<T> {
    Err(new_throwable(FAILED, JString::from(text), cause))
}

fn show(o: &JObject) -> JResult<String> {
    crate::lang::stringify::to_java_string(o)
}

/// JUnit の `expected: <x> but was: <y>`（文字列が同じなら型と識別ハッシュを付ける）。
fn expected_but_was(msg: &JObject, expected: &JObject, actual: &JObject) -> JResult<String> {
    let (e, a) = (show(expected)?, show(actual)?);
    let (e, a) = if e == a && !expected.is_null() && !actual.is_null() {
        (with_class(expected, &e)?, with_class(actual, &a)?)
    } else {
        (format!("<{e}>"), format!("<{a}>"))
    };
    Ok(format!("{}expected: {e} but was: {a}", prefix(msg)?))
}

fn with_class(o: &JObject, text: &str) -> JResult<String> {
    Ok(format!("{}@{:x}<{text}>", o.class_name()?, o.obj()?.base().identity_hash() as u32))
}

/// `assertEquals(expected, actual[, message])`（プリミティブはボクシングして比べる。Java と同じく Double は NaN 同士が等しい）。
pub fn assert_equals(expected: impl Into<JObject>, actual: impl Into<JObject>, msg: impl Into<JObject>) -> JResult<()> {
    let (e, a, msg) = (expected.into(), actual.into(), msg.into());
    if equals_nullable(&e, &a)? {
        return Ok(());
    }
    fail_with(expected_but_was(&msg, &e, &a)?, JObject::null())
}

/// `assertEquals(expected, actual, delta[, message])`（double / float）。
pub fn assert_equals_delta(expected: impl Into<JObject>, actual: impl Into<JObject>, delta: f64, msg: impl Into<JObject>) -> JResult<()> {
    let (e, a, msg) = (expected.into(), actual.into(), msg.into());
    let (x, y) = (crate::lang::boxed::number_f64(&e)?, crate::lang::boxed::number_f64(&a)?);
    if delta.is_nan() || delta < 0.0 {
        return fail_with(format!("positive delta expected but was: <{}>", show(&box_f64(delta))?), JObject::null());
    }
    if x.to_bits() == y.to_bits() || (x - y).abs() <= delta || (x.is_nan() && y.is_nan()) {
        return Ok(());
    }
    fail_with(expected_but_was(&msg, &e, &a)?, JObject::null())
}

/// `assertNotEquals(unexpected, actual[, message])`。
pub fn assert_not_equals(unexpected: impl Into<JObject>, actual: impl Into<JObject>, msg: impl Into<JObject>) -> JResult<()> {
    let (u, a, msg) = (unexpected.into(), actual.into(), msg.into());
    if !equals_nullable(&u, &a)? {
        return Ok(());
    }
    fail_with(format!("{}expected: not equal but was: <{}>", prefix(&msg)?, show(&a)?), JObject::null())
}

/// `assertTrue(condition[, message])` / `assertFalse`（expected は期待する値）。
pub fn assert_bool(expected: bool, condition: bool, msg: impl Into<JObject>) -> JResult<()> {
    if condition == expected {
        return Ok(());
    }
    fail_with(format!("{}expected: <{expected}> but was: <{condition}>", prefix(&msg.into())?), JObject::null())
}

/// `BooleanSupplier` の値。
pub fn boolean_supplier(s: &JObject) -> JResult<bool> {
    s.invoke("getAsBoolean", &[])?.unbox_bool()
}

/// `assertNull(actual[, message])`。
pub fn assert_null(actual: impl Into<JObject>, msg: impl Into<JObject>) -> JResult<()> {
    let a = actual.into();
    if a.is_null() {
        return Ok(());
    }
    fail_with(format!("{}expected: <null> but was: <{}>", prefix(&msg.into())?, show(&a)?), JObject::null())
}

/// `assertNotNull(actual[, message])`。
pub fn assert_not_null(actual: impl Into<JObject>, msg: impl Into<JObject>) -> JResult<()> {
    if !actual.into().is_null() {
        return Ok(());
    }
    fail_with(format!("{}expected: not <null>", prefix(&msg.into())?), JObject::null())
}

/// `assertSame(expected, actual[, message])`。
pub fn assert_same(expected: impl Into<JObject>, actual: impl Into<JObject>, msg: impl Into<JObject>) -> JResult<()> {
    let (e, a, msg) = (expected.into(), actual.into(), msg.into());
    if e.same(&a) {
        return Ok(());
    }
    fail_with(expected_but_was(&msg, &e, &a)?, JObject::null())
}

/// `assertNotSame(unexpected, actual[, message])`。
pub fn assert_not_same(unexpected: impl Into<JObject>, actual: impl Into<JObject>, msg: impl Into<JObject>) -> JResult<()> {
    let (u, a) = (unexpected.into(), actual.into());
    if !u.same(&a) {
        return Ok(());
    }
    fail_with(format!("{}expected: not same but was: <{}>", prefix(&msg.into())?, show(&a)?), JObject::null())
}

/// 配列の要素のボクシング（assertArrayEquals 用）。
pub trait BoxElem {
    fn boxed(&self) -> JObject;
}

macro_rules! box_elem {
    ($t:ty, $f:expr) => {
        impl BoxElem for $t {
            fn boxed(&self) -> JObject {
                $f(*self)
            }
        }
    };
}
box_elem!(i32, box_i32);
box_elem!(i64, box_i64);
box_elem!(i16, box_i16);
box_elem!(i8, box_i8);
box_elem!(u16, box_u16);
box_elem!(bool, box_bool);
box_elem!(f64, box_f64);
box_elem!(f32, box_f32);

impl BoxElem for JObject {
    fn boxed(&self) -> JObject {
        self.clone()
    }
}

impl BoxElem for JString {
    fn boxed(&self) -> JObject {
        JObject::from(self.clone())
    }
}

fn sequence_equals(kind: &str, e: Vec<JObject>, a: Vec<JObject>, msg: &JObject) -> JResult<()> {
    for (i, (x, y)) in e.iter().zip(a.iter()).enumerate() {
        if !equals_nullable(x, y)? {
            let p = prefix(msg)?;
            return fail_with(
                format!("{p}{kind} contents differ at index [{i}], expected: <{}> but was: <{}>", show(x)?, show(y)?),
                JObject::null(),
            );
        }
    }
    if e.len() != a.len() {
        let p = prefix(msg)?;
        return fail_with(format!("{p}{kind} lengths differ, expected: <{}> but was: <{}>", e.len(), a.len()), JObject::null());
    }
    Ok(())
}

/// `assertArrayEquals(expected, actual[, message])`。
pub fn assert_array_equals<T: Clone + BoxElem>(expected: &JArray<T>, actual: &JArray<T>, msg: impl Into<JObject>) -> JResult<()> {
    let msg = msg.into();
    match (expected.is_null(), actual.is_null()) {
        (true, true) => return Ok(()),
        (true, false) => return fail_with(format!("{}expected array was <null>", prefix(&msg)?), JObject::null()),
        (false, true) => return fail_with(format!("{}actual array was <null>", prefix(&msg)?), JObject::null()),
        _ => {}
    }
    let e = expected.to_vec()?.iter().map(BoxElem::boxed).collect();
    let a = actual.to_vec()?.iter().map(BoxElem::boxed).collect();
    let p = prefix(&msg)?;
    // JUnit は長さを先に比べる。
    let (el, al) = (expected.length()?, actual.length()?);
    if el != al {
        return fail_with(format!("{p}array lengths differ, expected: <{el}> but was: <{al}>"), JObject::null());
    }
    sequence_equals("array", e, a, &msg)
}

/// `assertIterableEquals(expected, actual[, message])`。
pub fn assert_iterable_equals(expected: &JObject, actual: &JObject, msg: impl Into<JObject>) -> JResult<()> {
    let msg = msg.into();
    let (e, a) = (crate::util::collections::to_vec(expected)?, crate::util::collections::to_vec(actual)?);
    sequence_equals("iterable", e, a, &msg)
}

fn class_name_of(class: &JObject) -> JResult<String> {
    Ok(crate::util::misc::class_get_name(class)?.as_str()?.to_string())
}

/// `assertThrows(type, executable[, message])` / `assertThrowsExactly`（送出された例外を返す）。
pub fn assert_throws(class: &JObject, executable: &JObject, msg: impl Into<JObject>, exact: bool) -> JResult<JObject> {
    let msg = msg.into();
    let name = class_name_of(class)?;
    match executable.invoke("execute", &[]) {
        Ok(_) => fail_with(format!("{}Expected {name} to be thrown, but nothing was thrown.", prefix(&msg)?), JObject::null()),
        Err(e) => {
            let matches = if exact { e.class_name()? == name } else { e.instance_of(&name) };
            if matches {
                return Ok(e);
            }
            fail_with(format!("{}Unexpected exception type thrown, expected: <{name}> but was: <{}>", prefix(&msg)?, e.class_name()?), e)
        }
    }
}

/// `assertDoesNotThrow(executable[, message])` / `assertDoesNotThrow(supplier)`（supplier なら値を返す）。
pub fn assert_does_not_throw(f: &JObject, method: &str, msg: impl Into<JObject>) -> JResult<JObject> {
    let msg = msg.into();
    match f.invoke(method, &[]) {
        Ok(v) => Ok(if method == "get" { v } else { JObject::null() }),
        Err(e) => {
            let m = crate::lang::throwable::get_message(&e)?;
            let suffix = if m.is_null() { String::new() } else { format!(": {}", m.as_str()?) };
            fail_with(format!("{}Unexpected exception thrown: {}{suffix}", prefix(&msg)?, e.class_name()?), e)
        }
    }
}

/// `assertInstanceOf(type, actual[, message])`。
pub fn assert_instance_of(class: &JObject, actual: impl Into<JObject>, msg: impl Into<JObject>) -> JResult<JObject> {
    let (a, msg) = (actual.into(), msg.into());
    let name = class_name_of(class)?;
    if a.instance_of(&name) {
        return Ok(a);
    }
    let was = if a.is_null() { "null".to_string() } else { a.class_name()?.to_string() };
    let what = if a.is_null() { "Unexpected null value" } else { "Unexpected type" };
    fail_with(format!("{}{what}, expected: <{name}> but was: <{was}>", prefix(&msg)?), JObject::null())
}

/// `assertAll([heading,] executables...)`: すべて実行し、失敗があれば MultipleFailuresError。
pub fn assert_all(heading: impl Into<JObject>, executables: Vec<JObject>) -> JResult<()> {
    let heading = heading.into();
    let mut failures = Vec::new();
    for e in executables {
        if let Err(err) = e.invoke("execute", &[]) {
            if !err.instance_of("java.lang.Exception") && !err.instance_of("java.lang.AssertionError") {
                return Err(err);
            }
            failures.push(err);
        }
    }
    if failures.is_empty() {
        return Ok(());
    }
    let head = match message(&heading)? {
        Some(h) if !h.trim().is_empty() => h,
        _ => "Multiple Failures".to_string(),
    };
    let n = failures.len();
    let mut text = format!("{head} ({n} failure{})", if n == 1 { "" } else { "s" });
    for f in &failures {
        text.push_str("\n\t");
        text.push_str(&show(f)?);
    }
    let err = new_throwable("org.opentest4j.MultipleFailuresError", JString::from(text), JObject::null());
    for f in failures {
        crate::lang::throwable::add_suppressed(&err, f)?;
    }
    Err(err)
}

/// `assertAll` の引数（配列・コレクション）。
pub fn executables_of_array(a: &JArray<JObject>) -> JResult<Vec<JObject>> {
    a.to_vec()
}

pub fn executables_of_collection(c: &JObject) -> JResult<Vec<JObject>> {
    crate::util::collections::to_vec(c)
}

/// `fail([message][, cause])`（戻り値の型は呼び出し側に合わせる）。
pub fn fail(msg: impl Into<JObject>, cause: impl Into<JObject>) -> JResult<JObject> {
    let text = message(&msg.into())?;
    let cause = cause.into();
    match text {
        Some(t) => fail_with(t, cause),
        None => Err(new_throwable(FAILED, JString::null(), cause)),
    }
}

/// `Assumptions.assumeTrue(condition[, message])` / `assumeFalse`。
pub fn assume(expected: bool, condition: bool, msg: impl Into<JObject>) -> JResult<()> {
    if condition == expected {
        return Ok(());
    }
    let text = match message(&msg.into())? {
        Some(m) => format!("Assumption failed: {m}"),
        None => "Assumption failed: assumption is not true".to_string(),
    };
    Err(new_throwable("org.opentest4j.TestAbortedException", JString::from(text), JObject::null()))
}
