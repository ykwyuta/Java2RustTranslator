//! プログラムの起動・終了、例外の送出と捕捉。
//!
//! Java の例外は `Result` で伝える。例外を送出しうる関数は [`JResult<T>`]（= `Result<T, JObject>`）を返し、
//! `Err` に例外オブジェクトを入れる。呼び出し側は `?` で呼び出し元へ伝え、`try` は [`try_block`] で
//! 本体（クロージャ）の `Err` を受け止める。`catch` 節の型判定は生成コード側で `instance_of` を使って行う。
//! パニックは Java の例外には使わない（変換できなかったコードの `todo!()` と、ランタイムの内部エラーだけ）。

use crate::array::JArray;
use crate::lang::string::JString;
use crate::lang::throwable::{describe_chain, new_throwable};
use crate::object::JObject;

/// 例外を送出しうる処理の結果（`Err` は送出された例外オブジェクト。null にはならない）。
pub type JResult<T> = Result<T, JObject>;

/// JDK の例外オブジェクトを作る。
pub fn exception(class_name: &'static str, message: Option<&str>) -> JObject {
    let msg = message.map(JString::from).unwrap_or_default();
    new_throwable(class_name, msg, JObject::null())
}

/// `throw e`。e が null なら NullPointerException を送出する。
pub fn throw_obj<T>(e: JObject) -> JResult<T> {
    if e.is_null() {
        return Err(exception("java.lang.NullPointerException", None));
    }
    Err(e)
}

/// JDK の例外を作って送出する（ランタイム内部と生成コード用）。
pub fn throw<T>(class_name: &'static str, message: Option<&str>) -> JResult<T> {
    Err(exception(class_name, message))
}

/// `NullPointerException` を送出する。
pub fn npe<T>() -> JResult<T> {
    throw("java.lang.NullPointerException", None)
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

/// `try { ... }`。本体が例外を送出したら `Err(例外)` を返す（本体の中の `?` はここで止まる）。
#[inline]
pub fn try_block<R>(body: impl FnOnce() -> JResult<Flow<R>>) -> JResult<Flow<R>> {
    body()
}

/// static 初期化（static フィールドの初期化子・static 初期化ブロック・enum 定数の生成）の結果。
///
/// static フィールドは最初に使われたときに初期化するので、そこで起きた例外は呼び出し元へ伝えられない。
/// Java の未捕捉の `ExceptionInInitializerError` と同じ表示をして、終了コード 1 で終わる。
pub fn static_init<T>(init: impl FnOnce() -> JResult<T>) -> T {
    match init() {
        Ok(v) => v,
        Err(e) => {
            crate::io::flush_stdout();
            eprintln!("Exception in thread \"main\" java.lang.ExceptionInInitializerError\nCaused by: {}", describe_chain(&e));
            std::process::exit(1)
        }
    }
}

/// クラスの初期化で送出された例外 e を `ExceptionInInitializerError` にする（Error はそのまま）。
pub fn initializer_error(e: JObject) -> JObject {
    if e.instance_of("java.lang.Error") {
        return e;
    }
    crate::lang::throwable::new_throwable("java.lang.ExceptionInInitializerError", JString::null(), e)
}

/// 初期化に失敗したクラスを再び使おうとした: `NoClassDefFoundError`。
pub fn no_class_def_found<T>(class_name: &str) -> JResult<T> {
    Err(crate::lang::throwable::new_throwable(
        "java.lang.NoClassDefFoundError",
        JString::from(format!("Could not initialize class {class_name}")),
        JObject::null(),
    ))
}

/// `main` の戻り値（例外を送出しない `main` は `()`、送出しうる `main` は `JResult<()>`）。
pub trait MainResult {
    fn into_result(self) -> JResult<()>;
}

impl MainResult for () {
    fn into_result(self) -> JResult<()> {
        Ok(())
    }
}

impl MainResult for JResult<()> {
    fn into_result(self) -> JResult<()> {
        self
    }
}

/// `public static void main(String[] args)` を実行する。
///
/// Java のスレッドと同程度に深い再帰ができるよう、大きなスタックを持つスレッドで実行する。
/// 未捕捉例外は Java と同様に `Exception in thread "main" ...` を標準エラーに出し、終了コード 1 で終わる。
pub fn run_main<R: MainResult, F: FnOnce(JArray<JString>) -> R + Send + 'static>(main: F) {
    // Rust のパニック（変換できなかったコードの todo!() など）でも、それまでの標準出力は書き出す。
    let default_hook = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        crate::io::flush_stdout();
        default_hook(info);
    }));
    let handle = std::thread::Builder::new()
        .name("main".to_string())
        .stack_size(1 << 30)
        .spawn(move || {
            let args: Vec<JString> = std::env::args().skip(1).map(JString::from).collect();
            let result = main(JArray::from_vec(args)).into_result();
            crate::io::flush_stdout();
            let code = match result {
                Ok(()) => 0,
                Err(e) => {
                    eprintln!("Exception in thread \"main\" {}", describe_chain(&e));
                    1
                }
            };
            // main が終わっても、デーモンでないスレッドが終わるまでプロセスは終わらない。
            crate::lang::thread::finish();
            crate::io::flush_stdout();
            code
        })
        .expect("failed to start the main thread");
    // Rust のパニックは標準のメッセージを出してから終了コード 101 で終わる。
    let code = handle.join().unwrap_or(101);
    if code != 0 {
        std::process::exit(code);
    }
}

/// `System.exit(status)`。
pub fn exit(status: i32) -> ! {
    crate::io::flush_stdout();
    std::process::exit(status)
}
