//! java.lang.Throwable とその JDK サブクラス。
//!
//! 例外オブジェクトは通常の `JObject`。JDK の例外（ArithmeticException など）は [`JdkThrowable`]、
//! ユーザー定義の例外クラスは、ルートの構造体に [`Throwable`]（メッセージと原因）を持たせて表す。

use crate::lang::string::JString;
use crate::object::{alloc, JObject, Object, ObjectBase};
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

    fn of(this: &JObject) -> &Throwable {
        match this.obj().as_throwable() {
            Some(t) => t,
            None => crate::object::bad_cast(this, "java.lang.Throwable"),
        }
    }

    /// `super(message, cause)` など、Throwable のコンストラクタ。
    pub fn init(this: &JObject, message: JString, cause: JObject) {
        let t = Throwable::of(this);
        *t.message.borrow_mut() = message;
        *t.cause.borrow_mut() = cause;
    }

    /// `super(cause)`: メッセージは cause.toString()。
    pub fn init_cause_only(this: &JObject, cause: JObject) {
        let msg = if cause.is_null() { JString::null() } else { cause.to_jstring() };
        Throwable::init(this, msg, cause);
    }
}

/// `getMessage()`。
pub fn get_message(this: &JObject) -> JString {
    Throwable::of(this).message.borrow().clone()
}

/// `getCause()`。
pub fn get_cause(this: &JObject) -> JObject {
    Throwable::of(this).cause.borrow().clone()
}

/// `initCause(cause)`。
pub fn init_cause(this: &JObject, cause: JObject) -> JObject {
    *Throwable::of(this).cause.borrow_mut() = cause;
    this.clone()
}

/// `addSuppressed(exception)`。
pub fn add_suppressed(this: &JObject, exception: JObject) {
    if exception.same(this) {
        crate::rt::throw("java.lang.IllegalArgumentException", Some("Self-suppression not permitted"));
    }
    if exception.is_null() {
        crate::rt::throw("java.lang.NullPointerException", Some("Cannot suppress a null exception."));
    }
    Throwable::of(this).suppressed.borrow_mut().push(exception);
}

/// `getSuppressed()`。
pub fn get_suppressed(this: &JObject) -> crate::array::JArray<JObject> {
    crate::array::JArray::from_vec(Throwable::of(this).suppressed.borrow().clone())
}

/// `Throwable.toString()`: `クラス名` または `クラス名: メッセージ`。
pub fn throwable_to_string(class_name: &str, t: &Throwable) -> JString {
    let m = t.message.borrow();
    if m.is_null() {
        JString::from(class_name)
    } else {
        JString::from(format!("{class_name}: {}", m.as_str()))
    }
}

/// `printStackTrace()`: 標準エラーに例外と原因の連鎖を出す（スタックトレースの行は出さない）。
pub fn print_stack_trace(this: &JObject) {
    crate::io::flush_stdout();
    eprintln!("{}", describe_chain(this));
}

/// 未捕捉例外などの表示用: `toString()` と `Caused by: ...` の連鎖。
pub fn describe_chain(this: &JObject) -> String {
    let mut out = this.to_jstring().to_string();
    if let Some(t) = this.obj().as_throwable() {
        for s in t.suppressed.borrow().iter() {
            out.push_str("\n\tSuppressed: ");
            out.push_str(&s.to_jstring().to_string());
        }
    }
    let mut cur = get_cause_opt(this);
    let mut depth = 0;
    while let Some(c) = cur {
        if depth > 32 {
            break;
        }
        out.push_str("\nCaused by: ");
        out.push_str(&c.to_jstring().to_string());
        cur = get_cause_opt(&c);
        depth += 1;
    }
    out
}

fn get_cause_opt(o: &JObject) -> Option<JObject> {
    let t = o.obj().as_throwable()?;
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
    fn to_jstring(&self) -> JString {
        throwable_to_string(self.class, &self.part)
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
pub fn new_throwable_with_cause(class: &'static str, cause: JObject) -> JObject {
    let msg = if cause.is_null() { JString::null() } else { cause.to_jstring() };
    new_throwable(class, msg, cause)
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
        "java.lang.NumberFormatException" => "java.lang.IllegalArgumentException",
        "java.util.InputMismatchException" => "java.util.NoSuchElementException",
        "java.lang.AssertionError" | "java.lang.LinkageError" | "java.lang.VirtualMachineError" => "java.lang.Error",
        "java.lang.StackOverflowError" | "java.lang.OutOfMemoryError" => "java.lang.VirtualMachineError",
        "java.lang.IncompatibleClassChangeError" => "java.lang.LinkageError",
        "java.lang.AbstractMethodError" => "java.lang.IncompatibleClassChangeError",
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
