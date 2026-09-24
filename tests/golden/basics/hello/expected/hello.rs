//! Translated from `Hello` (Hello.java) by Java2RustTranslator.

#[allow(unused_imports)]
use jrt::prelude::*;

pub struct Hello {
    pub(crate) __base: jrt::ObjectBase,
}

impl Hello {
    /// この型（またはサブクラス）のオブジェクトから、Hello の部分を取り出す。
    pub fn of(this: &JObject) -> &Hello {
        if let Some(x) = this.downcast_ref::<Hello>() {
            return x;
        }
        jrt::object::bad_cast(this, "Hello")
    }

    pub(crate) fn __alloc(base: jrt::ObjectBase) -> Hello {
        Hello {
            __base: base,
        }
    }

    pub fn new() -> JObject {
        let this = jrt::alloc(|base| Hello::__alloc(base));
        Hello::__init(&this);
        this
    }

    pub(crate) fn __init(this: &JObject) {}

    pub fn main(args: JArray<JString>) {
        jrt::io::system_out().println("Hello, World!");
    }
}

impl jrt::Object for Hello {
    fn base(&self) -> &jrt::ObjectBase {
        &self.__base
    }

    fn class_name(&self) -> &'static str {
        "Hello"
    }

    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "Hello" | "java.lang.Object")
    }
}
