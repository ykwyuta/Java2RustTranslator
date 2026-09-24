//! Translated from `Mixed` (Mixed.java) by Java2RustTranslator.

#[allow(unused_imports)]
use jrt::prelude::*;

thread_local! {
    pub static NAMES: std::cell::RefCell<JArray<JString>> = std::cell::RefCell::new(JArray::<JString>::new(3));
}

thread_local! {
    pub static TITLE: std::cell::RefCell<JString> = std::cell::RefCell::new(JString::null());
}

pub struct Mixed {
    pub(crate) __base: jrt::ObjectBase,
    pub(crate) value: std::cell::Cell<i32>,
}

impl Mixed {
    /// この型（またはサブクラス）のオブジェクトから、Mixed の部分を取り出す。
    pub fn of(this: &JObject) -> &Mixed {
        if let Some(x) = this.downcast_ref::<Mixed>() {
            return x;
        }
        jrt::object::bad_cast(this, "Mixed")
    }

    pub(crate) fn __alloc(base: jrt::ObjectBase) -> Mixed {
        Mixed {
            __base: base,
            value: std::cell::Cell::new(0),
        }
    }

    pub fn new(v: i32) -> JObject {
        let this = jrt::alloc(|base| Mixed::__alloc(base));
        Mixed::__init(&this, v);
        this
    }

    pub(crate) fn __init(this: &JObject, v: i32) {
        Mixed::of(this).value.set(v);
    }

    pub fn get(this: &JObject) -> i32 {
        Mixed::of(this).value.get()
    }

    pub fn try_it(s: JString) -> i32 {
        match jrt::try_block(|| -> jrt::Flow<i32> {
            return jrt::Flow::Return(jrt::lang::number::parse_int(&s));
            jrt::Flow::Normal
        }) {
            Ok(jrt::Flow::Return(__v)) => return __v,
            Ok(_) => {}
            Err(__e) => {
                if __e.instance_of("java.lang.NumberFormatException") {
                    let e: JObject = __e;
                    return -1;
                } else {
                    jrt::throw_obj(__e);
                }
            }
        }
        unreachable!()
    }

    pub fn main(args: JArray<JString>) {
        let list: JObject = jrt::util::collections::new_list(jrt::util::collections::ListKind::ArrayList);
        list.add(jrt::box_i32(1));
        let boxed: JObject = jrt::box_i32(5);
        let m: JObject = Mixed::new(3);
        jrt::io::system_out().println(&Mixed::get(m.nn()).wrapping_add(list.size()).wrapping_add(boxed.clone().unbox_i32()));
        jrt::io::system_out().println(&jrt::lang::format::format(&jstr!("%d"), &JArray::from_vec(vec![jrt::box_i32(3)])));
        let s: JString = JString::null();
        let r: JObject = jrt::lambda(&["java.lang.Runnable"], move |__args: &[JObject]| -> JObject {
            jrt::io::system_out().println("x");
            JObject::null()
        });
        jrt::io::system_out().println(&Mixed::try_it(jstr!("12")).wrapping_add(NAMES.with(|c| c.borrow().clone()).length()));
    }
}

impl jrt::Object for Mixed {
    fn base(&self) -> &jrt::ObjectBase {
        &self.__base
    }

    fn class_name(&self) -> &'static str {
        "Mixed"
    }

    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "Mixed" | "java.lang.Object")
    }
}
