//! Translated from `Hello` (Hello.java) by Java2RustTranslator.

#[allow(unused_imports)]
use jrt::prelude::*;

pub fn main(args: JArray<JString>) {
    jrt::io::system_out().println("Hello, World!");
}
