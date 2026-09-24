//! Java のオブジェクト参照（`JObject`）と、全オブジェクトが実装するトレイト `Object`。
//!
//! 変換後のコードでは、String と配列以外の参照型（ユーザー定義クラス、インタフェース、`Object`、
//! ボクシングされた値、コレクション、型変数など）はすべて `JObject` で表す。`JObject` は
//! Java と同じく `null` を取りうる共有参照で、`clone()` は参照の複製（同じオブジェクトを指す）。

use crate::lang::string::JString;
use crate::lang::stringify::JStringify;
use crate::rt::throw;
use std::any::Any;
use std::cell::Cell;
use std::rc::{Rc, Weak};

/// すべての Java オブジェクトが持つ共通部分（自分自身への弱参照と識別ハッシュ）。
pub struct ObjectBase {
    this: Weak<dyn Object>,
    hash: Cell<i32>,
}

thread_local! {
    static HASH_SEED: Cell<u32> = const { Cell::new(0x2545_f491) };
}

impl ObjectBase {
    pub fn new(this: Weak<dyn Object>) -> ObjectBase {
        ObjectBase { this, hash: Cell::new(0) }
    }

    /// 自分自身への参照（Java の `this`）。
    pub fn this(&self) -> JObject {
        JObject(self.this.upgrade())
    }

    /// `System.identityHashCode`。Java と同様に値は実行ごとの実装依存なので、決定的な擬似乱数にしている。
    pub fn identity_hash(&self) -> i32 {
        if self.hash.get() == 0 {
            let h = HASH_SEED.with(|s| {
                // xorshift32
                let mut x = s.get();
                x ^= x << 13;
                x ^= x >> 17;
                x ^= x << 5;
                s.set(x);
                x & 0x7fff_ffff
            });
            self.hash.set(if h == 0 { 1 } else { h as i32 });
        }
        self.hash.get()
    }
}

/// Java のオブジェクト。変換器はクラスごとにこのトレイトを実装する。
///
/// `to_jstring` / `equals` / `hash_code` / `compare_to` は `java.lang.Object` と `Comparable` の
/// メソッドに対応し、ユーザーのクラスが上書きしていればその実装を呼ぶ。`invoke` は JDK 側（コレクションの
/// 比較関数、`Iterable` など）から、ユーザーのクラスやラムダが実装したインタフェースのメソッドを呼ぶための入口。
pub trait Object: Any {
    fn base(&self) -> &ObjectBase;

    /// `getClass().getName()`。
    fn class_name(&self) -> &'static str;

    /// `instanceof`（クラス名は完全修飾名。スーパークラス・インタフェースを含む）。
    fn instance_of(&self, class: &str) -> bool {
        class == "java.lang.Object" || class == self.class_name()
    }

    /// `toString()`。
    fn to_jstring(&self) -> JString {
        default_to_string(self.class_name(), self.base().identity_hash())
    }

    /// `equals(Object)`（既定は同一性）。
    fn equals(&self, other: &JObject) -> bool {
        other.0.as_ref().is_some_and(|o| std::ptr::addr_eq(Rc::as_ptr(o), self as *const Self))
    }

    /// `hashCode()`（既定は識別ハッシュ）。
    fn hash_code(&self) -> i32 {
        self.base().identity_hash()
    }

    /// `Comparable.compareTo(Object)`。
    fn compare_to(&self, _other: &JObject) -> i32 {
        throw(
            "java.lang.ClassCastException",
            Some(&format!("class {} cannot be cast to class java.lang.Comparable", self.class_name())),
        )
    }

    /// インタフェースのメソッドを名前で呼ぶ（引数・戻り値はボクシング済み）。実装していなければ None。
    fn invoke(&self, _method: &str, _args: &[JObject]) -> Option<JObject> {
        None
    }

    /// `Throwable` のサブクラスなら、その状態（メッセージ・原因）。
    fn as_throwable(&self) -> Option<&crate::lang::throwable::Throwable> {
        None
    }

    /// `enum` なら、その名前と序数。
    fn as_enum(&self) -> Option<&crate::lang::enums::EnumBase> {
        None
    }
}

/// `Object.toString()` の既定の書式（`クラス名@16進ハッシュ`）。
pub fn default_to_string(class_name: &str, hash: i32) -> JString {
    JString::from(format!("{class_name}@{:x}", hash as u32))
}

/// Java の参照（`null` を取りうる共有参照）。
#[derive(Clone, Default)]
pub struct JObject(Option<Rc<dyn Object>>);

/// オブジェクトを作る。`f` には新しいオブジェクトの共通部分が渡される。
pub fn alloc<T: Object>(f: impl FnOnce(ObjectBase) -> T) -> JObject {
    JObject(Some(alloc_rc(f)))
}

/// オブジェクトを作り、具体型の `Rc` のまま返す（ランタイム内部用）。
pub fn alloc_rc<T: Object>(f: impl FnOnce(ObjectBase) -> T) -> Rc<T> {
    Rc::new_cyclic(|w: &Weak<T>| {
        let w: Weak<dyn Object> = w.clone();
        f(ObjectBase::new(w))
    })
}

impl JObject {
    /// `null`。
    pub const fn null() -> JObject {
        JObject(None)
    }

    pub fn from_rc(rc: Rc<dyn Object>) -> JObject {
        JObject(Some(rc))
    }

    pub fn is_null(&self) -> bool {
        self.0.is_none()
    }

    /// 参照先。null なら NullPointerException。
    pub fn obj(&self) -> &dyn Object {
        match &self.0 {
            Some(o) => &**o,
            None => throw("java.lang.NullPointerException", None),
        }
    }

    /// null でないことを確かめて自分を返す（メソッド呼び出しのレシーバ用）。
    pub fn nn(&self) -> &JObject {
        if self.0.is_none() {
            throw("java.lang.NullPointerException", None);
        }
        self
    }

    /// 具体型への参照（null や別の型なら None）。
    pub fn downcast_ref<T: Object>(&self) -> Option<&T> {
        let o: &dyn Any = self.0.as_deref()?;
        o.downcast_ref::<T>()
    }

    /// 具体型の `Rc`（ランタイム内部用）。
    pub fn downcast_rc<T: Object>(&self) -> Option<Rc<T>> {
        let rc: Rc<dyn Any> = self.0.clone()?;
        rc.downcast::<T>().ok()
    }

    /// 実行時の型がちょうど `T` か。
    pub fn is<T: Object>(&self) -> bool {
        self.downcast_ref::<T>().is_some()
    }

    /// Java の `==`（参照の同一性）。
    pub fn same(&self, other: &JObject) -> bool {
        match (&self.0, &other.0) {
            (None, None) => true,
            (Some(a), Some(b)) => std::ptr::addr_eq(Rc::as_ptr(a), Rc::as_ptr(b)),
            _ => false,
        }
    }

    /// `x instanceof C`（null は false）。
    pub fn instance_of(&self, class: &str) -> bool {
        self.0.as_ref().is_some_and(|o| o.instance_of(class))
    }

    /// `(C) x`。null はそのまま通し、型が違えば ClassCastException。
    pub fn checkcast(&self, class: &str) -> JObject {
        if let Some(o) = &self.0 {
            if !o.instance_of(class) {
                throw(
                    "java.lang.ClassCastException",
                    Some(&format!("class {} cannot be cast to class {class}", o.class_name())),
                );
            }
        }
        self.clone()
    }

    pub fn class_name(&self) -> &'static str {
        self.obj().class_name()
    }

    /// `x.toString()`（null なら NullPointerException）。
    pub fn to_jstring(&self) -> JString {
        self.obj().to_jstring()
    }

    /// `x.equals(y)`。
    pub fn equals(&self, other: &JObject) -> bool {
        self.obj().equals(other)
    }

    /// `x.hashCode()`。
    pub fn hash_code(&self) -> i32 {
        self.obj().hash_code()
    }

    /// `x.compareTo(y)`（Comparable）。
    pub fn compare_to(&self, other: &JObject) -> i32 {
        self.obj().compare_to(other)
    }

    /// 関数型インタフェースなどのメソッドを名前で呼ぶ。実装されていなければ AbstractMethodError。
    pub fn invoke(&self, method: &str, args: &[JObject]) -> JObject {
        match self.obj().invoke(method, args) {
            Some(r) => r,
            // java.lang.Object / Comparable のメソッドは、どのオブジェクトでも呼べる。
            None if method == "compareTo" && args.len() == 1 => crate::box_i32(self.compare_to(&args[0])),
            None if method == "equals" && args.len() == 1 => crate::box_bool(self.equals(&args[0])),
            None if method == "hashCode" && args.is_empty() => crate::box_i32(self.hash_code()),
            None if method == "toString" && args.is_empty() => JObject::from(self.to_jstring()),
            None => throw(
                "java.lang.AbstractMethodError",
                Some(&format!("{}.{method}", self.class_name())),
            ),
        }
    }

    /// 関数型インタフェースのメソッドを呼べるか（ラムダ・メソッドを実装したオブジェクト）。
    pub fn try_invoke(&self, method: &str, args: &[JObject]) -> Option<JObject> {
        self.obj().invoke(method, args)
    }
}

/// `Objects.equals(a, b)` 相当（null 安全な equals）。
pub fn equals_nullable(a: &JObject, b: &JObject) -> bool {
    if a.is_null() {
        b.is_null()
    } else {
        a.equals(b)
    }
}

/// `Objects.hashCode(o)` 相当。
pub fn hash_nullable(o: &JObject) -> i32 {
    if o.is_null() {
        0
    } else {
        o.hash_code()
    }
}

/// 参照型への変換で型が合わなかった場合（生成コードのキャスト失敗）。
pub fn bad_cast(obj: &JObject, class: &str) -> ! {
    throw(
        "java.lang.ClassCastException",
        Some(&format!("class {} cannot be cast to class {class}", obj.class_name())),
    )
}

impl JStringify for JObject {
    fn append_to(&self, out: &mut String) {
        match &self.0 {
            None => out.push_str("null"),
            Some(o) => out.push_str(o.to_jstring().as_str()),
        }
    }
}

impl std::fmt::Debug for JObject {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let mut s = String::new();
        self.append_to(&mut s);
        f.write_str(&s)
    }
}

// ------------------------------------------------------------------ String / 配列を Object として扱う

/// `Object` として扱われる String（`Object o = "x"`、`List<String>` の要素など）。
struct StrObj {
    base: ObjectBase,
    s: JString,
}

impl Object for StrObj {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.lang.String"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(
            class,
            "java.lang.String" | "java.lang.Object" | "java.lang.CharSequence" | "java.lang.Comparable" | "java.io.Serializable"
        )
    }
    fn to_jstring(&self) -> JString {
        self.s.clone()
    }
    fn equals(&self, other: &JObject) -> bool {
        other.downcast_ref::<StrObj>().is_some_and(|o| o.s == self.s)
    }
    fn hash_code(&self) -> i32 {
        self.s.hash_code()
    }
    fn compare_to(&self, other: &JObject) -> i32 {
        self.s.compare_to(&other.cast_string())
    }
}

impl From<JString> for JObject {
    fn from(s: JString) -> JObject {
        if s.is_null() {
            JObject::null()
        } else {
            alloc(|base| StrObj { base, s })
        }
    }
}

impl JObject {
    /// `(String) o`。null は null の String になる。
    pub fn cast_string(&self) -> JString {
        match &self.0 {
            None => JString::null(),
            Some(_) => match self.downcast_ref::<StrObj>() {
                Some(s) => s.s.clone(),
                None => bad_cast(self, "java.lang.String"),
            },
        }
    }

    /// Object として扱われる配列 → 配列。
    pub fn cast_array<T: Clone + 'static>(&self) -> crate::JArray<T> {
        match &self.0 {
            None => crate::JArray::null(),
            Some(_) => match self.downcast_ref::<ArrObj<T>>() {
                Some(a) => a.a.clone(),
                None => bad_cast(self, "array"),
            },
        }
    }

    /// 配列 → Object。
    pub fn from_array<T: Clone + 'static>(a: crate::JArray<T>) -> JObject {
        if a.is_null() {
            JObject::null()
        } else {
            alloc(|base| ArrObj { base, a })
        }
    }
}

struct ArrObj<T: 'static> {
    base: ObjectBase,
    a: crate::JArray<T>,
}

/// Rust の要素型から Java の型名を求める（`i32` → `int`、`JArray<i32>` → `int[]`）。
fn java_type_name(rust: &str) -> String {
    let r = rust.rsplit("::").next().unwrap_or(rust);
    if let Some(inner) = rust.strip_prefix("jrt::array::JArray<").and_then(|x| x.strip_suffix('>')) {
        return format!("{}[]", java_type_name(inner));
    }
    match r {
        "i32" => "int",
        "i64" => "long",
        "i16" => "short",
        "i8" => "byte",
        "u16" => "char",
        "f64" => "double",
        "f32" => "float",
        "bool" => "boolean",
        "JString" => "java.lang.String",
        _ => "java.lang.Object",
    }
    .to_string()
}

impl<T: Clone + 'static> Object for ArrObj<T> {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "[Ljava.lang.Object;"
    }
    fn instance_of(&self, class: &str) -> bool {
        let elem = java_type_name(std::any::type_name::<T>());
        let me = format!("{elem}[]");
        if class == me || matches!(class, "java.lang.Object" | "java.lang.Cloneable" | "java.io.Serializable") {
            return true;
        }
        // 参照型の配列は共変（String[] は Object[] でもある）。
        let reference = !matches!(elem.as_str(), "int" | "long" | "short" | "byte" | "char" | "double" | "float" | "boolean");
        reference && class == "java.lang.Object[]"
    }
    fn equals(&self, other: &JObject) -> bool {
        other.downcast_ref::<ArrObj<T>>().is_some_and(|o| crate::JArray::ptr_eq(&o.a, &self.a))
    }
}
