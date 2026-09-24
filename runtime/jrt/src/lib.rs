//! jrt: Java2RustTranslator が生成する Rust コードのためのランタイム。
//!
//! Java の意味論（整数演算、文字列、配列、オブジェクト参照と null、例外、ラムダ、コレクション、
//! 標準入出力など）を安全な Rust だけで提供する。対応表は docs/03-translation-rules.md を参照。
#![forbid(unsafe_code)]

pub mod array;
pub mod io;
pub mod lambda;
pub mod lang;
pub mod num;
pub mod object;
pub mod rt;
pub mod util;

pub use array::JArray;
pub use lambda::lambda;
pub use lang::boxed::{box_bool, box_f32, box_f64, box_i16, box_i32, box_i64, box_i8, box_u16};
pub use lang::string::JString;
pub use lang::stringify::{JChar, JStringify};
pub use object::{alloc, JObject, Object, ObjectBase};
pub use rt::{run_main, throw, throw_obj, try_block, Flow};

/// 変換器が扱えない Java の型の代わりに置くプレースホルダ型。
#[derive(Clone, Debug, Default)]
pub struct Unsupported;

/// 変換できなかった式の代わり。`todo!()` と違って型を明示できるので、ジェネリックな引数の位置でも型検査を通る。
pub fn unsupported<T>(what: &str) -> T {
    panic!("not yet implemented: {what}")
}

/// 生成コードが `use jrt::prelude::*;` で取り込む名前。
pub mod prelude {
    pub use crate::array::JArray;
    pub use crate::lang::string::JString;
    pub use crate::lang::stringify::JChar;
    pub use crate::object::JObject;
    pub use crate::object::Object as _;
    pub use crate::util::collections::Collections as _;
    pub use crate::{jconcat, jstr};
}

/// 文字列リテラル → `JString`（const 文脈でも使える）。
#[macro_export]
macro_rules! jstr {
    ($s:literal) => {
        $crate::JString::from_static($s)
    };
}

/// Java の文字列連結 `a + b + c`。各引数は参照で受け取り、Java の文字列変換規則で連結する。
#[macro_export]
macro_rules! jconcat {
    ($($e:expr),* $(,)?) => {{
        let mut __j2r_s = ::std::string::String::new();
        $( $crate::JStringify::append_to(&$e, &mut __j2r_s); )*
        $crate::JString::from(__j2r_s)
    }};
}

/// 到達しないコード（todo!() の後）でも型検査を通すための実装。
impl lang::stringify::JStringify for Unsupported {
    fn append_to(&self, out: &mut String) {
        out.push_str("<unsupported>")
    }
}
