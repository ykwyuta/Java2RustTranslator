//! ラムダ式・メソッド参照。
//!
//! どの関数型インタフェースのラムダも同じ [`Lambda`] で表す。引数と戻り値はボクシングした `JObject` で受け渡し、
//! 生成コードがインタフェースのメソッドの型に合わせて変換する（void のメソッドは null を返す）。

use crate::object::{alloc, JObject, Object, ObjectBase};
use crate::rt::JResult;

pub struct Lambda {
    base: ObjectBase,
    /// 実装しているインタフェース（instanceof 用）。
    ifaces: &'static [&'static str],
    f: Box<dyn Fn(&[JObject]) -> JResult<JObject>>,
}

impl Object for Lambda {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "$$Lambda"
    }
    fn instance_of(&self, class: &str) -> bool {
        class == "java.lang.Object" || self.ifaces.contains(&class)
    }
    fn invoke(&self, _method: &str, args: &[JObject]) -> JResult<Option<JObject>> {
        Ok(Some((self.f)(args)?))
    }
}

/// ラムダを作る（本体は例外を `Err` で返す）。
pub fn lambda(ifaces: &'static [&'static str], f: impl Fn(&[JObject]) -> JResult<JObject> + 'static) -> JObject {
    alloc(|base| Lambda { base, ifaces, f: Box::new(f) })
}

/// 関数型インタフェースの値を呼ぶ（ラムダでも、インタフェースを実装したオブジェクトでもよい）。
pub fn call(f: &JObject, method: &str, args: &[JObject]) -> JResult<JObject> {
    f.invoke(method, args)
}
