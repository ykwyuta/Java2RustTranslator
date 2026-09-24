//! Translated from `Mixed` (Mixed.java) by Java2RustTranslator.

#[allow(unused_imports)]
use jrt::prelude::*;

thread_local! {
    pub static NAMES: std::cell::RefCell<JArray<JString>> = std::cell::RefCell::new(JArray::null());
}

thread_local! {
    pub static TITLE: std::cell::RefCell<JString> = std::cell::RefCell::new(JString::null());
}

thread_local! {
    static __CLINIT: std::cell::Cell<u8> = std::cell::Cell::new(0);
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

    /// クラスの初期化（static フィールドの初期化子と static 初期化ブロック）。最初の 1 回だけ実行する。
    pub fn __clinit() -> JResult<()> {
        let state = __CLINIT.with(|c| c.get());
        if state == 1 {
            return Ok(());
        }
        if state == 2 {
            return jrt::rt::no_class_def_found("Mixed");
        }
        __CLINIT.with(|c| c.set(1));
        if let Err(e) = (|| -> JResult<()> {
            { let __v = JArray::<JString>::new(3)?; NAMES.with(|c| *c.borrow_mut() = __v) };
            Ok(())
        })() {
            __CLINIT.with(|c| c.set(2));
            return Err(jrt::rt::initializer_error(e));
        }
        Ok(())
    }

    pub fn new(v: i32) -> JResult<JObject> {
        Mixed::__clinit()?;
        let this = jrt::alloc(|base| Mixed::__alloc(base));
        Mixed::__init(&this, v)?;
        Ok(this)
    }

    pub(crate) fn __init(this: &JObject, v: i32) -> JResult<()> {
        Mixed::of(this).value.set(v);
        Ok(())
    }

    pub fn get(this: &JObject) -> i32 {
        Mixed::of(this).value.get()
    }

    pub fn try_it(s: JString) -> JResult<i32> {
        Mixed::__clinit()?;
        match jrt::try_block(|| -> JResult<jrt::Flow<i32>> {
            return Ok(jrt::Flow::Return(jrt::lang::number::parse_int(&s)?));
            Ok(jrt::Flow::Normal)
        }) {
            Ok(jrt::Flow::Return(__v)) => return Ok(__v),
            Ok(_) => {}
            Err(__e) => {
                if __e.instance_of("java.lang.NumberFormatException") {
                    let e: JObject = __e;
                    return Ok(-1);
                } else {
                    return Err(__e);
                }
            }
        }
        unreachable!()
    }

    pub fn main(args: JArray<JString>) -> JResult<()> {
        Mixed::__clinit()?;
        let list: JObject = jrt::util::collections::new_list(jrt::util::collections::ListKind::ArrayList);
        list.add(jrt::box_i32(1))?;
        let boxed: JObject = jrt::box_i32(5);
        let m: JObject = Mixed::new(3)?;
        jrt::io::system_out().println(&Mixed::get(m.nn()?).wrapping_add(list.size()?).wrapping_add(boxed.clone().unbox_i32()?));
        jrt::io::system_out().println(&jrt::lang::format::format(&jstr!("%d"), &JArray::from_vec(vec![jrt::box_i32(3)]))?);
        let s: JString = JString::null();
        let r: JObject = jrt::lambda(&["java.lang.Runnable"], move |__args: &[JObject]| -> JResult<JObject> {
            jrt::io::system_out().println("x");
            Ok(JObject::null())
        });
        jrt::io::system_out().println(&Mixed::try_it(jstr!("12"))?.wrapping_add(NAMES.with(|c| c.borrow().clone()).length()?));
        Ok(())
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
