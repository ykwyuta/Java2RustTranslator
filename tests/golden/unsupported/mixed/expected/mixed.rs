//! Translated from `Mixed` (Mixed.java) by Java2RustTranslator.

#[allow(unused_imports)]
use jrt::prelude::*;

thread_local! {
    pub static NAMES: std::cell::RefCell<JArray<JString>> = std::cell::RefCell::new(JArray::<JString>::new(3));
}

thread_local! {
    pub static TITLE: std::cell::RefCell<JString> = std::cell::RefCell::new(JString::default());
}

pub fn try_it(s: JString) -> i32 {
    todo!("J2R: try/catch/finally is not supported yet");
}

pub fn main(args: JArray<JString>) {
    let list: jrt::Unsupported = todo!("J2R: new java.util.ArrayList");
    todo!("J2R: call java.util.List.add(java.lang.Object)");
    let boxed: jrt::Unsupported = todo!("J2R: boxing int -> java.lang.Integer");
    let m: jrt::Unsupported = todo!("J2R: object creation of 'Mixed' is not supported yet");
    jrt::io::system_out().println(&jrt::unsupported::<i32>("J2R: arithmetic on boxed values is not supported yet"));
    jrt::io::system_out().println(&jrt::unsupported::<JString>("J2R: varargs call java.lang.String.format(java.lang.String,java.lang.Object[]) is not supported yet"));
    let s: JString = todo!("J2R: null is not supported yet");
    let r: jrt::Unsupported = todo!("J2R: expression 'LAMBDA_EXPRESSION' is not supported yet");
    jrt::io::system_out().println(&try_it(jstr!("12")).wrapping_add(NAMES.with(|c| c.borrow().clone()).length()));
}
