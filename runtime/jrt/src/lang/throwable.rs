//! java.lang.Throwable とその JDK サブクラス。
//!
//! 例外オブジェクトは通常の `JObject`。JDK の例外（ArithmeticException など）は [`JdkThrowable`]、
//! ユーザー定義の例外クラスは、ルートの構造体に [`Throwable`]（メッセージと原因）を持たせて表す。

use crate::lang::string::JString;
use crate::object::{alloc, JObject, Object, ObjectBase};
use crate::rt::JResult;
use std::cell::RefCell;

/// Throwable の状態（メッセージ・原因・抑制された例外）。
#[derive(Default)]
pub struct Throwable {
    message: RefCell<JString>,
    cause: RefCell<JObject>,
    suppressed: RefCell<Vec<JObject>>,
}

impl Throwable {
    pub fn new_part() -> Throwable {
        Throwable::default()
    }

    fn of(this: &JObject) -> JResult<&Throwable> {
        match this.obj()?.as_throwable() {
            Some(t) => Ok(t),
            None => crate::object::class_cast(this, "java.lang.Throwable"),
        }
    }

    /// `super(message, cause)` など、Throwable のコンストラクタ。
    pub fn init(this: &JObject, message: JString, cause: JObject) {
        if let Some(t) = this.downcast_throwable() {
            *t.message.borrow_mut() = message;
            *t.cause.borrow_mut() = cause;
        }
    }

    /// `super(cause)`: メッセージは cause.toString()。
    pub fn init_cause_only(this: &JObject, cause: JObject) -> JResult<()> {
        let msg = if cause.is_null() { JString::null() } else { cause.to_jstring()? };
        Throwable::init(this, msg, cause);
        Ok(())
    }
}

impl JObject {
    fn downcast_throwable(&self) -> Option<&Throwable> {
        self.obj().ok()?.as_throwable()
    }
}

/// `getMessage()`。
pub fn get_message(this: &JObject) -> JResult<JString> {
    Ok(Throwable::of(this)?.message.borrow().clone())
}

/// `getCause()`。
pub fn get_cause(this: &JObject) -> JResult<JObject> {
    Ok(Throwable::of(this)?.cause.borrow().clone())
}

/// `initCause(cause)`。
pub fn init_cause(this: &JObject, cause: JObject) -> JResult<JObject> {
    *Throwable::of(this)?.cause.borrow_mut() = cause;
    Ok(this.clone())
}

/// `addSuppressed(exception)`。
pub fn add_suppressed(this: &JObject, exception: JObject) -> JResult<()> {
    let t = Throwable::of(this)?;
    if exception.same(this) {
        return crate::rt::throw("java.lang.IllegalArgumentException", Some("Self-suppression not permitted"));
    }
    if exception.is_null() {
        return crate::rt::throw("java.lang.NullPointerException", Some("Cannot suppress a null exception."));
    }
    t.suppressed.borrow_mut().push(exception);
    Ok(())
}

/// `getSuppressed()`。
pub fn get_suppressed(this: &JObject) -> JResult<crate::array::JArray<JObject>> {
    Ok(crate::array::JArray::from_vec(Throwable::of(this)?.suppressed.borrow().clone()))
}

/// `Throwable.toString()`: `クラス名` または `クラス名: メッセージ`。
pub fn throwable_to_string(class_name: &str, t: &Throwable) -> JString {
    let m = t.message.borrow();
    match m.opt_str() {
        None => JString::from(class_name),
        Some(msg) => JString::from(format!("{class_name}: {msg}")),
    }
}

/// `printStackTrace()`: 標準エラーに例外と原因の連鎖を出す（スタックトレースの行は出さない）。
pub fn print_stack_trace(this: &JObject) -> JResult<()> {
    this.obj()?;
    crate::io::flush_stdout();
    eprintln!("{}", describe_chain(this));
    Ok(())
}

/// 表示用の toString()（toString() が例外を送出したら `クラス名@ハッシュ`）。
fn show(o: &JObject) -> String {
    match o.to_jstring() {
        Ok(s) => s.as_str_or_null().to_string(),
        Err(_) => format!("{o:?}"),
    }
}

/// 未捕捉例外などの表示用: `toString()` と `Suppressed: ...`・`Caused by: ...` の連鎖。
pub fn describe_chain(this: &JObject) -> String {
    let mut out = show(this);
    if let Some(t) = this.downcast_throwable() {
        for s in t.suppressed.borrow().iter() {
            out.push_str("\n\tSuppressed: ");
            out.push_str(&show(s));
        }
    }
    let mut cur = get_cause_opt(this);
    let mut depth = 0;
    while let Some(c) = cur {
        if depth > 32 {
            break;
        }
        out.push_str("\nCaused by: ");
        out.push_str(&show(&c));
        cur = get_cause_opt(&c);
        depth += 1;
    }
    out
}

fn get_cause_opt(o: &JObject) -> Option<JObject> {
    let t = o.downcast_throwable()?;
    let c = t.cause.borrow().clone();
    if c.is_null() || c.same(o) {
        None
    } else {
        Some(c)
    }
}

/// JDK の例外クラスのインスタンス。
pub struct JdkThrowable {
    base: ObjectBase,
    class: &'static str,
    part: Throwable,
}

impl Object for JdkThrowable {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        self.class
    }
    fn instance_of(&self, class: &str) -> bool {
        jdk_instance_of(self.class, class)
    }
    fn to_jstring(&self) -> JResult<JString> {
        Ok(throwable_to_string(self.class, &self.part))
    }
    fn as_throwable(&self) -> Option<&Throwable> {
        Some(&self.part)
    }
}

/// `new X(message, cause)`（X は JDK の例外クラス）。
pub fn new_throwable(class: &'static str, message: JString, cause: JObject) -> JObject {
    alloc(|base| JdkThrowable { base, class, part: Throwable { message: RefCell::new(message), cause: RefCell::new(cause), ..Default::default() } })
}

/// `new X(cause)`: メッセージは cause.toString()。
pub fn new_throwable_with_cause(class: &'static str, cause: JObject) -> JResult<JObject> {
    let msg = if cause.is_null() { JString::null() } else { cause.to_jstring()? };
    Ok(new_throwable(class, msg, cause))
}

/// JDK の主な例外クラスの親クラス。
pub fn jdk_super(class: &str) -> Option<&'static str> {
    Some(match class {
        "java.lang.Throwable" => "java.lang.Object",
        "java.lang.Exception" | "java.lang.Error" => "java.lang.Throwable",
        "java.lang.RuntimeException"
        | "java.io.IOException"
        | "java.lang.InterruptedException"
        | "java.lang.CloneNotSupportedException"
        | "java.lang.ReflectiveOperationException"
        | "java.util.concurrent.TimeoutException"
        | "java.util.concurrent.ExecutionException" => "java.lang.Exception",
        "java.lang.ClassNotFoundException" => "java.lang.ReflectiveOperationException",
        "java.io.FileNotFoundException" | "java.io.EOFException" => "java.io.IOException",
        "java.lang.ArithmeticException"
        | "java.lang.ClassCastException"
        | "java.lang.IllegalArgumentException"
        | "java.lang.IllegalStateException"
        | "java.lang.IndexOutOfBoundsException"
        | "java.lang.NegativeArraySizeException"
        | "java.lang.NullPointerException"
        | "java.lang.UnsupportedOperationException"
        | "java.lang.ArrayStoreException"
        | "java.lang.SecurityException"
        | "java.util.NoSuchElementException"
        | "java.util.ConcurrentModificationException"
        | "java.util.EmptyStackException"
        | "java.io.UncheckedIOException"
        | "java.time.DateTimeException" => "java.lang.RuntimeException",
        "java.lang.ArrayIndexOutOfBoundsException" | "java.lang.StringIndexOutOfBoundsException" => {
            "java.lang.IndexOutOfBoundsException"
        }
        "java.lang.NumberFormatException" | "java.util.IllegalFormatException" | "java.util.regex.PatternSyntaxException" => "java.lang.IllegalArgumentException",
        "java.util.IllegalFormatConversionException"
        | "java.util.MissingFormatArgumentException"
        | "java.util.UnknownFormatConversionException" => "java.util.IllegalFormatException",
        "java.util.InputMismatchException" => "java.util.NoSuchElementException",
        "java.lang.AssertionError" | "java.lang.LinkageError" | "java.lang.VirtualMachineError" => "java.lang.Error",
        "java.lang.StackOverflowError" | "java.lang.OutOfMemoryError" => "java.lang.VirtualMachineError",
        "java.lang.IncompatibleClassChangeError"
        | "java.lang.ExceptionInInitializerError"
        | "java.lang.NoClassDefFoundError" => "java.lang.LinkageError",
        "java.lang.AbstractMethodError" => "java.lang.IncompatibleClassChangeError",
        "org.opentest4j.AssertionFailedError" | "org.opentest4j.MultipleFailuresError" => "java.lang.AssertionError",
        "org.opentest4j.TestAbortedException" => "java.lang.RuntimeException",
        _ => return None,
    })
}

/// JDK の例外クラス `class` が `target` のサブタイプか（未知のクラスは RuntimeException の直下とみなす）。
pub fn jdk_instance_of(class: &str, target: &str) -> bool {
    if target == "java.io.Serializable" {
        return true;
    }
    let mut cur = class;
    for _ in 0..16 {
        if cur == target {
            return true;
        }
        cur = match jdk_super(cur) {
            Some(s) => s,
            None if cur == "java.lang.Object" => return false,
            None => "java.lang.RuntimeException",
        };
    }
    false
}
