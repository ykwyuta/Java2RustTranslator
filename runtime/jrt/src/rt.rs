//! プログラムの起動・終了と、未捕捉例外（パニック）の扱い。

use crate::array::JArray;
use crate::lang::string::JString;
use std::panic;

/// パニックのペイロードとして使う Java 例外の表現。
#[derive(Debug)]
pub struct JavaException {
    pub class_name: String,
    pub message: Option<String>,
}

impl std::fmt::Display for JavaException {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match &self.message {
            Some(m) => write!(f, "{}: {}", self.class_name, m),
            None => write!(f, "{}", self.class_name),
        }
    }
}

/// 例外を送出する。現在は catch を未サポートのため、未捕捉例外としてスレッドを終了させる。
pub fn throw(class_name: &str, message: Option<&str>) -> ! {
    panic::panic_any(JavaException {
        class_name: class_name.to_string(),
        message: message.map(str::to_string),
    })
}

/// `throw new X(msg)` の変換先。
pub fn throw_new(class_name: &str, message: Option<JString>) -> ! {
    let m = message.map(|s| s.as_str().to_string());
    throw(class_name, m.as_deref())
}

/// `public static void main(String[] args)` を実行する。
///
/// 未捕捉例外は Java と同様に `Exception in thread "main" ...` を標準エラーに出し、終了コード 1 で終了する。
pub fn run_main<F: FnOnce(JArray<JString>)>(main: F) {
    panic::set_hook(Box::new(|info| {
        crate::io::flush_stdout();
        let payload = info.payload();
        let text = if let Some(e) = payload.downcast_ref::<JavaException>() {
            e.to_string()
        } else if let Some(s) = payload.downcast_ref::<&str>() {
            format!("java.lang.Error: {s}")
        } else if let Some(s) = payload.downcast_ref::<String>() {
            format!("java.lang.Error: {s}")
        } else {
            "java.lang.Error".to_string()
        };
        eprintln!("Exception in thread \"main\" {text}");
        if let Some(loc) = info.location() {
            eprintln!("\tat <translated code> ({}:{})", loc.file(), loc.line());
        }
    }));
    let args: Vec<JString> = std::env::args().skip(1).map(JString::from).collect();
    let result = panic::catch_unwind(panic::AssertUnwindSafe(|| main(JArray::from_vec(args))));
    crate::io::flush_stdout();
    if result.is_err() {
        std::process::exit(1);
    }
}

/// `System.exit(status)`。
pub fn exit(status: i32) -> ! {
    crate::io::flush_stdout();
    std::process::exit(status)
}
