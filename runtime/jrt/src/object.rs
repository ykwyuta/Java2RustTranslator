//! Java のオブジェクト参照（`JObject`）と、全オブジェクトが実装するトレイト `Object`。
//!
//! 変換後のコードでは、String と配列以外の参照型（ユーザー定義クラス、インタフェース、`Object`、
//! ボクシングされた値、コレクション、型変数など）はすべて `JObject` で表す。`JObject` は
//! Java と同じく `null` を取りうる共有参照で、`clone()` は参照の複製（同じオブジェクトを指す）。

use crate::lang::string::JString;
use crate::rt::{npe, throw, JResult};
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
/// ユーザーのコードを実行しうるメソッドは、例外を伝えるため [`JResult`] を返す。
pub trait Object: Any {
    fn base(&self) -> &ObjectBase;

    /// `getClass().getName()`。
    fn class_name(&self) -> &'static str;

    /// `instanceof`（クラス名は完全修飾名。スーパークラス・インタフェースを含む）。
    fn instance_of(&self, class: &str) -> bool {
        class == "java.lang.Object" || class == self.class_name()
    }

    /// `toString()`。
    fn to_jstring(&self) -> JResult<JString> {
        Ok(default_to_string(self.class_name(), self.base().identity_hash()))
    }

    /// `equals(Object)`（既定は同一性）。
    fn equals(&self, other: &JObject) -> JResult<bool> {
        Ok(other.0.as_ref().is_some_and(|o| std::ptr::addr_eq(Rc::as_ptr(o), self as *const Self)))
    }

    /// `hashCode()`（既定は識別ハッシュ）。
    fn hash_code(&self) -> JResult<i32> {
        Ok(self.base().identity_hash())
    }

    /// `Comparable.compareTo(Object)`。
    fn compare_to(&self, _other: &JObject) -> JResult<i32> {
        throw(
            "java.lang.ClassCastException",
            Some(&format!("class {} cannot be cast to class java.lang.Comparable", self.class_name())),
        )
    }

    /// インタフェースのメソッドを名前で呼ぶ（引数・戻り値はボクシング済み）。実装していなければ `Ok(None)`。
    fn invoke(&self, _method: &str, _args: &[JObject]) -> JResult<Option<JObject>> {
        Ok(None)
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

/// `super.toString()`（`java.lang.Object` の toString。null なら "null"）。
pub fn default_to_string_of(o: &JObject) -> JString {
    match &o.0 {
        Some(x) => default_to_string(x.class_name(), x.base().identity_hash()),
        None => JString::from("null"),
    }
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
    pub fn obj(&self) -> JResult<&dyn Object> {
        match &self.0 {
            Some(o) => Ok(&**o),
            None => npe(),
        }
    }

    /// null でないことを確かめて自分を返す（メソッド呼び出し・フィールドアクセスのレシーバ用）。
    pub fn nn(&self) -> JResult<&JObject> {
        if self.0.is_none() {
            return npe();
        }
        Ok(self)
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
    pub fn checkcast(&self, class: &str) -> JResult<JObject> {
        if let Some(o) = &self.0 {
            if !o.instance_of(class) {
                return class_cast(self, class);
            }
        }
        Ok(self.clone())
    }

    /// `x.getClass().getName()`（null なら NullPointerException）。
    pub fn class_name(&self) -> JResult<&'static str> {
        Ok(self.obj()?.class_name())
    }

    /// `x.toString()`（null なら NullPointerException）。
    pub fn to_jstring(&self) -> JResult<JString> {
        self.obj()?.to_jstring()
    }

    /// `x.equals(y)`。
    pub fn equals(&self, other: &JObject) -> JResult<bool> {
        self.obj()?.equals(other)
    }

    /// `x.hashCode()`。
    pub fn hash_code(&self) -> JResult<i32> {
        self.obj()?.hash_code()
    }

    /// `x.compareTo(y)`（Comparable）。
    pub fn compare_to(&self, other: &JObject) -> JResult<i32> {
        self.obj()?.compare_to(other)
    }

    /// 関数型インタフェースなどのメソッドを名前で呼ぶ。実装されていなければ AbstractMethodError。
    pub fn invoke(&self, method: &str, args: &[JObject]) -> JResult<JObject> {
        match self.obj()?.invoke(method, args)? {
            Some(r) => Ok(r),
            // java.lang.Object / Comparable のメソッドは、どのオブジェクトでも呼べる。
            None if method == "compareTo" && args.len() == 1 => Ok(crate::box_i32(self.compare_to(&args[0])?)),
            None if method == "equals" && args.len() == 1 => Ok(crate::box_bool(self.equals(&args[0])?)),
            None if method == "hashCode" && args.is_empty() => Ok(crate::box_i32(self.hash_code()?)),
            None if method == "toString" && args.is_empty() => Ok(JObject::from(self.to_jstring()?)),
            None => throw(
                "java.lang.AbstractMethodError",
                Some(&format!("{}.{method}", self.class_name()?)),
            ),
        }
    }

    /// 関数型インタフェースのメソッドを呼ぶ（ラムダ・メソッドを実装したオブジェクト）。実装していなければ `Ok(None)`。
    pub fn try_invoke(&self, method: &str, args: &[JObject]) -> JResult<Option<JObject>> {
        self.obj()?.invoke(method, args)
    }
}

/// `Objects.equals(a, b)` 相当（null 安全な equals）。
pub fn equals_nullable(a: &JObject, b: &JObject) -> JResult<bool> {
    if a.is_null() {
        Ok(b.is_null())
    } else {
        a.equals(b)
    }
}

/// `Objects.hashCode(o)` 相当。
pub fn hash_nullable(o: &JObject) -> JResult<i32> {
    if o.is_null() {
        Ok(0)
    } else {
        o.hash_code()
    }
}

/// `(C) x` の失敗: ClassCastException を送出する（メッセージは HotSpot と同じ形式）。
pub fn class_cast<T>(obj: &JObject, class: &str) -> JResult<T> {
    let from = obj.0.as_ref().map_or("null", |o| o.class_name());
    let place = |c: &str| {
        if c.starts_with("java.") || c.starts_with('[') {
            "module java.base of loader 'bootstrap'"
        } else {
            "unnamed module of loader 'app'"
        }
    };
    let detail = if place(from) == place(class) {
        format!("{from} and {class} are in {}", place(from))
    } else {
        format!("{from} is in {}; {class} is in {}", place(from), place(class))
    };
    throw("java.lang.ClassCastException", Some(&format!("class {from} cannot be cast to class {class} ({detail})")))
}

/// 生成コードの内部エラー（`C::of` や仮想呼び出しの振り分けで、型検査を通ったはずの値の型が違う）。
/// Java のプログラムからは起こりえないので、例外ではなくパニックにする。
pub fn bad_cast(obj: &JObject, class: &str) -> ! {
    let from = obj.0.as_ref().map_or("null", |o| o.class_name());
    panic!("internal error: {from} is not an instance of {class}")
}

impl std::fmt::Debug for JObject {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match &self.0 {
            None => f.write_str("null"),
            Some(o) => match o.to_jstring() {
                Ok(s) => f.write_str(s.as_str_or_null()),
                Err(_) => write!(f, "{}@{:x}", o.class_name(), o.base().identity_hash() as u32),
            },
        }
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
    fn to_jstring(&self) -> JResult<JString> {
        Ok(self.s.clone())
    }
    fn equals(&self, other: &JObject) -> JResult<bool> {
        Ok(other.downcast_ref::<StrObj>().is_some_and(|o| o.s == self.s))
    }
    fn hash_code(&self) -> JResult<i32> {
        self.s.hash_code()
    }
    fn compare_to(&self, other: &JObject) -> JResult<i32> {
        self.s.compare_to(&other.cast_string()?)
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
    pub fn cast_string(&self) -> JResult<JString> {
        match &self.0 {
            None => Ok(JString::null()),
            Some(_) => match self.downcast_ref::<StrObj>() {
                Some(s) => Ok(s.s.clone()),
                None => class_cast(self, "java.lang.String"),
            },
        }
    }

    /// Object として扱われる配列 → 配列。
    pub fn cast_array<T: Clone + 'static>(&self) -> JResult<crate::JArray<T>> {
        match &self.0 {
            None => Ok(crate::JArray::null()),
            Some(_) => match self.downcast_ref::<ArrObj<T>>() {
                Some(a) => Ok(a.a.clone()),
                None => {
                    let target = format!("{}[]", java_type_name(std::any::type_name::<T>()));
                    class_cast(self, &target)
                }
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
    fn equals(&self, other: &JObject) -> JResult<bool> {
        Ok(other.downcast_ref::<ArrObj<T>>().is_some_and(|o| crate::JArray::ptr_eq(&o.a, &self.a)))
    }
}

// ------------------------------------------------------------------ StringBuilder などを Object として扱う

/// Object として扱える、ランタイムの値型（StringBuilder・Scanner・PrintStream）。
pub trait NativeClass: Clone + 'static {
    /// Java のクラス名。
    const CLASS: &'static str;
    /// 実装しているインタフェース（instanceof 用）。
    const INTERFACES: &'static [&'static str] = &[];
    fn native_to_string(&self) -> Option<JString> {
        None
    }
}

/// Object として扱われるランタイムの値（中身は共有されるので、変更は元の値にも反映される）。
struct Native<T: NativeClass> {
    base: ObjectBase,
    v: T,
}

impl<T: NativeClass> Object for Native<T> {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        T::CLASS
    }
    fn instance_of(&self, class: &str) -> bool {
        class == "java.lang.Object" || class == T::CLASS || T::INTERFACES.contains(&class)
    }
    fn to_jstring(&self) -> JResult<JString> {
        Ok(self.v.native_to_string().unwrap_or_else(|| default_to_string(T::CLASS, self.base.identity_hash())))
    }
}

impl JObject {
    /// ランタイムの値（StringBuilder など）を Object にする。
    pub fn from_native<T: NativeClass>(v: T) -> JObject {
        alloc(|base| Native { base, v })
    }

    /// Object → ランタイムの値（null はそのまま既定値にせず NullPointerException にしない: Java の参照の null を表せないので、
    /// null は型の既定値になる）。型が違えば ClassCastException。
    pub fn cast_native<T: NativeClass + Default>(&self) -> JResult<T> {
        match &self.0 {
            None => Ok(T::default()),
            Some(_) => match self.downcast_ref::<Native<T>>() {
                Some(n) => Ok(n.v.clone()),
                None => class_cast(self, T::CLASS),
            },
        }
    }
}

impl NativeClass for crate::lang::StringBuilder {
    const CLASS: &'static str = "java.lang.StringBuilder";
    const INTERFACES: &'static [&'static str] = &["java.lang.CharSequence", "java.lang.Appendable", "java.lang.Comparable"];
    fn native_to_string(&self) -> Option<JString> {
        Some(self.to_jstring())
    }
}

impl NativeClass for crate::util::Scanner {
    const CLASS: &'static str = "java.util.Scanner";
    const INTERFACES: &'static [&'static str] = &["java.io.Closeable", "java.lang.AutoCloseable", "java.util.Iterator"];
}

impl NativeClass for crate::io::PrintStream {
    const CLASS: &'static str = "java.io.PrintStream";
    const INTERFACES: &'static [&'static str] = &["java.io.Closeable", "java.lang.AutoCloseable", "java.lang.Appendable"];
}

impl From<crate::lang::StringBuilder> for JObject {
    fn from(v: crate::lang::StringBuilder) -> JObject {
        JObject::from_native(v)
    }
}
