//! Translated from `Loops` (Loops.java) by Java2RustTranslator.

#[allow(unused_imports)]
use jrt::prelude::*;

pub struct Loops {
    pub(crate) __base: jrt::ObjectBase,
}

impl Loops {
    /// この型（またはサブクラス）のオブジェクトから、Loops の部分を取り出す。
    pub fn of(this: &JObject) -> &Loops {
        if let Some(x) = this.downcast_ref::<Loops>() {
            return x;
        }
        jrt::object::bad_cast(this, "Loops")
    }

    pub(crate) fn __alloc(base: jrt::ObjectBase) -> Loops {
        Loops {
            __base: base,
        }
    }

    pub fn new() -> JObject {
        let this = jrt::alloc(|base| Loops::__alloc(base));
        Loops::__init(&this);
        this
    }

    pub(crate) fn __init(this: &JObject) {}

    pub fn sum(values: JArray<i32>) -> JResult<i32> {
        let mut total: i32 = 0;
        {
            let __j2r_arr0: JArray<i32> = values.clone();
            let mut __j2r_i0: i32 = 0;
            while __j2r_i0 < __j2r_arr0.length()? {
                let v: i32 = __j2r_arr0.get(__j2r_i0)?;
                total = total.wrapping_add(v);
                __j2r_i0 = __j2r_i0.wrapping_add(1);
            }
        }
        Ok(total)
    }

    pub fn main(args: JArray<JString>) -> JResult<()> {
        {
            let mut i: i32 = 0;
            while i < 3 {
                'outer_body: {
                    {
                        let mut j: i32 = 0;
                        while j < 3 {
                            if j == 2 {
                                break 'outer_body;
                            }
                            jrt::io::system_out().println(&jconcat!(i, ",", j));
                            j = j.wrapping_add(1);
                        }
                    }
                }
                i = i.wrapping_add(1);
            }
        }
        let mut k: i32 = 0;
        loop {
            k = k.wrapping_add(1);
            if !(k < 5) {
                break;
            }
        }
        jrt::io::system_out().println(&Loops::sum(JArray::from_vec(vec![1, 2, 3]))?.wrapping_add(k));
        Ok(())
    }
}

impl jrt::Object for Loops {
    fn base(&self) -> &jrt::ObjectBase {
        &self.__base
    }

    fn class_name(&self) -> &'static str {
        "Loops"
    }

    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "Loops" | "java.lang.Object")
    }
}
