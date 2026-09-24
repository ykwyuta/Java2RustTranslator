//! プログラムの起動・終了、例外の送出と捕捉。
//!
//! Java の例外は Rust のパニック（巻き戻し）で伝播させる。例外オブジェクト（`JObject`）は `Send` でないため
//! パニックのペイロードには入れず、スレッドローカルの「送出中の例外」に置いてから巻き戻しを始める。
//! `try` は [`try_block`] で受け止め、`catch` 節の型判定は生成コード側で `instance_of` を使って行う。

use crate::array::JArray;
use crate::lang::string::JString;
use crate::lang::throwable::{describe_chain, new_throwable};
use crate::object::JObject;
use std::cell::RefCell;
use std::panic;

thread_local! {
    static PENDING: RefCell<Option<JObject>> = const { RefCell::new(None) };
    static PANIC_MESSAGE: RefCell<Option<String>> = const { RefCell::new(None) };
}

/// 巻き戻し中の Java 例外であることを示すペイロード。
struct JavaThrow;

/// `throw e`。e が null なら NullPointerException を送出する。
pub fn throw_obj(e: JObject) -> ! {
    let e = if e.is_null() { new_throwable("java.lang.NullPointerException", JString::null(), JObject::null()) } else { e };
    PENDING.with(|p| *p.borrow_mut() = Some(e));
    panic::resume_unwind(Box::new(JavaThrow))
}

/// JDK の例外を作って送出する（ランタイム内部と生成コードの `throw new X(msg)` 用）。
pub fn throw(class_name: &'static str, message: Option<&str>) -> ! {
    let msg = message.map(JString::from).unwrap_or_default();
    throw_obj(new_throwable(class_name, msg, JObject::null()))
}

/// try ブロック（クロージャ）の終わり方。`return` / `break` / `continue` をクロージャの外へ伝える。
pub enum Flow<R> {
    /// ブロックの最後まで実行した。
    Normal,
    /// `return v`。
    Return(R),
    /// 外側のループ・ブロックへの `break`（番号は生成コードが割り当てる）。
    Break(u32),
    /// 外側のループへの `continue`。
    Continue(u32),
}

/// `try { ... }`。本体が例外を送出したら `Err(例外)` を返す。
pub fn try_block<R>(body: impl FnOnce() -> Flow<R>) -> Result<Flow<R>, JObject> {
    match panic::catch_unwind(panic::AssertUnwindSafe(body)) {
        Ok(flow) => Ok(flow),
        Err(payload) => Err(caught(payload)),
    }
}

/// パニックのペイロードを Java の例外オブジェクトにする。Java の例外でない Rust のパニック
/// （todo!() や変換器・ランタイムの不具合）は `java.lang.Error` として扱う。
fn caught(payload: Box<dyn std::any::Any + Send>) -> JObject {
    if payload.is::<JavaThrow>() {
        if let Some(e) = PENDING.with(|p| p.borrow_mut().take()) {
            return e;
        }
    }
    let msg = PANIC_MESSAGE
        .with(|m| m.borrow_mut().take())
        .or_else(|| payload.downcast_ref::<&str>().map(|s| s.to_string()))
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "unknown panic".to_string());
    new_throwable("java.lang.Error", JString::from(msg), JObject::null())
}

/// `public static void main(String[] args)` を実行する。
///
/// Java のスレッドと同程度に深い再帰ができるよう、大きなスタックを持つスレッドで実行する。
/// 未捕捉例外は Java と同様に `Exception in thread "main" ...` を標準エラーに出し、終了コード 1 で終わる。
pub fn run_main<F: FnOnce(JArray<JString>) + Send + 'static>(main: F) {
    panic::set_hook(Box::new(|info| {
        let msg = if let Some(s) = info.payload().downcast_ref::<&str>() {
            s.to_string()
        } else if let Some(s) = info.payload().downcast_ref::<String>() {
            s.clone()
        } else {
            "panic".to_string()
        };
        let loc = info.location().map(|l| format!(" ({}:{})", l.file(), l.line())).unwrap_or_default();
        PANIC_MESSAGE.with(|m| *m.borrow_mut() = Some(format!("{msg}{loc}")));
    }));
    let handle = std::thread::Builder::new()
        .name("main".to_string())
        .stack_size(1 << 30)
        .spawn(move || {
            let args: Vec<JString> = std::env::args().skip(1).map(JString::from).collect();
            let result = try_block(|| {
                main(JArray::from_vec(args));
                Flow::<()>::Normal
            });
            crate::io::flush_stdout();
            match result {
                Ok(_) => 0,
                Err(e) => {
                    let text = panic::catch_unwind(panic::AssertUnwindSafe(|| describe_chain(&e)))
                        .unwrap_or_else(|_| "java.lang.Throwable".to_string());
                    eprintln!("Exception in thread \"main\" {text}");
                    1
                }
            }
        })
        .expect("failed to start the main thread");
    let code = handle.join().unwrap_or(1);
    if code != 0 {
        std::process::exit(code);
    }
}

/// `System.exit(status)`。
pub fn exit(status: i32) -> ! {
    crate::io::flush_stdout();
    std::process::exit(status)
}
