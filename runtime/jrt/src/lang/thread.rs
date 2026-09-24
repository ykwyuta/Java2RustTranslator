//! java.lang.Thread と java.util.concurrent（ExecutorService / Future / CompletableFuture / CountDownLatch /
//! Semaphore / atomic / ThreadLocal / Lock）。
//!
//! 生成コードのオブジェクトは `Rc` で共有され、static フィールドはスレッドローカルなので、Java のスレッドを OS の
//! スレッドにはできない。そこで 1 つの OS スレッドの上で、決まった順に実行する（決定的なスケジューラ）:
//! `start()` / `submit()` はキューに入れるだけで、`join()` / `sleep()` / `Future.get()` / `CountDownLatch.await()`
//! などで待つとき、およびプログラムの終わり（main が戻った後。JVM が非デーモンスレッドを待つのと同じ）に、
//! キューのスレッド・タスクを入れた順に最後まで実行する。スレッドの未捕捉例外は Java と同じく
//! `Exception in thread "Thread-0" ...` を標準エラーに出し、プログラムは続ける。
//! 実行の順序（インタリーブ）が結果を左右するプログラムでは、JVM の実行結果の 1 つと一致する（常に同じ結果になる）。

use crate::lang::string::JString;
use crate::object::{alloc, JObject, Object, ObjectBase};
use crate::rt::{throw, JResult};
use std::cell::{Cell, RefCell};
use std::collections::VecDeque;

// ------------------------------------------------------------------ スレッド

/// スレッドの状態（java.lang.Thread のオブジェクト、またはそれを継承したユーザーのクラスの委譲先が持つ）。
pub struct JThread {
    base: ObjectBase,
    name: RefCell<JString>,
    target: JObject,
    /// start() を呼んだオブジェクト（Thread を継承したクラスなら、そのオブジェクト）。
    outer: RefCell<JObject>,
    started: Cell<bool>,
    done: Cell<bool>,
    running: Cell<bool>,
    daemon: Cell<bool>,
    interrupted: Cell<bool>,
    priority: Cell<i32>,
    id: i64,
}

impl Object for JThread {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.lang.Thread"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.lang.Thread" | "java.lang.Runnable")
    }
    fn to_jstring(&self) -> JResult<JString> {
        Ok(JString::from(format!("Thread[#{},{},{},main]", self.id, self.name.borrow().as_str_or_null(), self.priority.get())))
    }
    fn invoke(&self, method: &str, args: &[JObject]) -> JResult<Option<JObject>> {
        match (method, args.len()) {
            ("run", 0) => {
                if !self.target.is_null() {
                    self.target.invoke("run", &[])?;
                }
                Ok(Some(JObject::null()))
            }
            _ => Ok(None),
        }
    }
}

enum Job {
    Thread(JObject),
    Task(JObject),
}

struct Scheduler {
    queue: VecDeque<Job>,
    /// 実行中のスレッドのオブジェクト（入れ子で実行するのでスタック）。空なら main スレッド。
    current: Vec<JObject>,
    main: Option<JObject>,
    thread_counter: i32,
    pool_counter: i32,
    next_id: i64,
}

thread_local! {
    static SCHED: RefCell<Scheduler> = RefCell::new(Scheduler {
        queue: VecDeque::new(),
        current: Vec::new(),
        main: None,
        thread_counter: 0,
        pool_counter: 0,
        next_id: 22,
    });
}

fn make_thread(target: JObject, name: Option<JString>) -> JObject {
    let (name, id) = SCHED.with(|s| {
        let mut s = s.borrow_mut();
        let name = name.unwrap_or_else(|| {
            let n = s.thread_counter;
            s.thread_counter += 1;
            JString::from(format!("Thread-{n}"))
        });
        s.next_id += 1;
        (name, s.next_id - 1)
    });
    alloc(|base| JThread {
        base,
        name: RefCell::new(name),
        target,
        outer: RefCell::new(JObject::null()),
        started: Cell::new(false),
        done: Cell::new(false),
        running: Cell::new(false),
        daemon: Cell::new(false),
        interrupted: Cell::new(false),
        priority: Cell::new(5),
        id,
    })
}

/// `new Thread()` / `new Thread(runnable)` / `new Thread(runnable, name)` / `new Thread(name)`（name は null 可）。
pub fn new_thread(target: impl Into<JObject>, name: &JString) -> JResult<JObject> {
    Ok(make_thread(target.into(), if name.is_null() { None } else { Some(name.clone()) }))
}

/// Thread のオブジェクト（Thread を継承したクラスなら、委譲先の Thread）。
fn thread_of(o: &JObject) -> JResult<&JThread> {
    if let Some(t) = o.downcast_ref::<JThread>() {
        return Ok(t);
    }
    o.obj()?;
    crate::object::class_cast(o, "java.lang.Thread")
}

/// Thread の状態に対する操作（Thread を継承したクラスなら、委譲先の Thread の状態）。
fn with_thread<R>(o: &JObject, f: impl FnOnce(&JThread) -> JResult<R>) -> JResult<R> {
    f(thread_of(o)?)
}

/// `thread.start()`。
pub fn start(o: &JObject) -> JResult<()> {
    with_thread(o, |t| {
        if t.started.get() {
            return throw("java.lang.IllegalThreadStateException", None);
        }
        t.started.set(true);
        *t.outer.borrow_mut() = o.clone();
        Ok(())
    })?;
    SCHED.with(|s| s.borrow_mut().queue.push_back(Job::Thread(o.clone())));
    Ok(())
}

/// `thread.run()`（Runnable を渡したなら、その run）。
pub fn run(o: &JObject) -> JResult<()> {
    let target = with_thread(o, |t| Ok(t.target.clone()))?;
    if !target.is_null() {
        target.invoke("run", &[])?;
    }
    Ok(())
}

fn report_uncaught(name: &str, e: &JObject) {
    crate::io::flush_stdout();
    eprintln!("Exception in thread \"{name}\" {}", crate::lang::throwable::describe_chain(e));
}

/// スレッド o を最後まで実行する（呼び出し側でキューから外してある）。
fn run_thread(o: &JObject) {
    let _ = with_thread(o, |t| {
        t.running.set(true);
        Ok(())
    });
    SCHED.with(|s| s.borrow_mut().current.push(o.clone()));
    let r = o.invoke("run", &[]);
    SCHED.with(|s| s.borrow_mut().current.pop());
    let _ = with_thread(o, |t| {
        t.running.set(false);
        t.done.set(true);
        Ok(())
    });
    if let Err(e) = r {
        let name = with_thread(o, |t| Ok(t.name.borrow().as_str_or_null().to_string())).unwrap_or_default();
        report_uncaught(&name, &e);
    }
}

/// キューの先頭の 1 つを実行する（なければ false）。
fn run_one() -> bool {
    let job = SCHED.with(|s| s.borrow_mut().queue.pop_front());
    match job {
        Some(Job::Thread(t)) => {
            run_thread(&t);
            true
        }
        Some(Job::Task(f)) => {
            run_task(&f);
            true
        }
        None => false,
    }
}

/// キューが空になるまで実行する（`Thread.sleep` / `yield` / プログラムの終わり）。
pub fn run_pending() {
    while run_one() {}
}

/// `Thread.sleep(ms)` / `Thread.yield()` / `TimeUnit.X.sleep(n)`（ほかのスレッドを実行する）。
pub fn sleep(ms: i64) -> JResult<()> {
    if ms < 0 {
        return throw("java.lang.IllegalArgumentException", Some("timeout value is negative"));
    }
    run_pending();
    Ok(())
}

/// `thread.join()`: そのスレッドが終わるまで、キューを順に実行する。
pub fn join(o: &JObject) -> JResult<()> {
    loop {
        let (started, done, running) = with_thread(o, |t| Ok((t.started.get(), t.done.get(), t.running.get())))?;
        if !started || done || running {
            return Ok(());
        }
        if !run_one() {
            return Ok(());
        }
    }
}

/// `Thread.currentThread()`。
pub fn current_thread() -> JObject {
    if let Some(t) = SCHED.with(|s| s.borrow().current.last().cloned()) {
        return t;
    }
    if let Some(m) = SCHED.with(|s| s.borrow().main.clone()) {
        return m;
    }
    let m = make_thread(JObject::null(), Some(JString::from("main")));
    if let Some(t) = m.downcast_ref::<JThread>() {
        t.started.set(true);
        t.running.set(true);
    }
    SCHED.with(|s| s.borrow_mut().main = Some(m.clone()));
    m
}

pub fn get_name(o: &JObject) -> JResult<JString> {
    with_thread(o, |t| Ok(t.name.borrow().clone()))
}

pub fn set_name(o: &JObject, name: &JString) -> JResult<()> {
    name.as_str()?;
    with_thread(o, |t| {
        *t.name.borrow_mut() = name.clone();
        Ok(())
    })
}

pub fn get_id(o: &JObject) -> JResult<i64> {
    with_thread(o, |t| Ok(t.id))
}

pub fn is_alive(o: &JObject) -> JResult<bool> {
    with_thread(o, |t| Ok(t.started.get() && !t.done.get()))
}

pub fn set_daemon(o: &JObject, on: bool) -> JResult<()> {
    with_thread(o, |t| {
        if t.started.get() {
            return throw("java.lang.IllegalThreadStateException", None);
        }
        t.daemon.set(on);
        Ok(())
    })
}

pub fn is_daemon(o: &JObject) -> JResult<bool> {
    with_thread(o, |t| Ok(t.daemon.get()))
}

pub fn set_priority(o: &JObject, p: i32) -> JResult<()> {
    if !(1..=10).contains(&p) {
        return throw("java.lang.IllegalArgumentException", None);
    }
    with_thread(o, |t| {
        t.priority.set(p);
        Ok(())
    })
}

pub fn get_priority(o: &JObject) -> JResult<i32> {
    with_thread(o, |t| Ok(t.priority.get()))
}

pub fn interrupt(o: &JObject) -> JResult<()> {
    with_thread(o, |t| {
        t.interrupted.set(true);
        Ok(())
    })
}

pub fn is_interrupted(o: &JObject) -> JResult<bool> {
    with_thread(o, |t| Ok(t.interrupted.get()))
}

/// `Thread.interrupted()`（現在のスレッドの割り込み状態を読んで消す）。
pub fn interrupted() -> JResult<bool> {
    let cur = current_thread();
    with_thread(&cur, |t| Ok(t.interrupted.replace(false)))
}

/// `Thread` を継承したクラスの `super(...)`（委譲先の Thread を作る）。
pub fn new_delegate(target: impl Into<JObject>, name: &JString) -> JResult<JObject> {
    new_thread(target, name)
}

/// プログラムの終わり: 残っているスレッド・タスクを実行する（非デーモンスレッドの終了を待つ JVM と同じ）。
pub fn finish() {
    run_pending();
}

/// `Thread.ofVirtual().start(r)` / `Thread.startVirtualThread(r)`。
pub fn start_virtual(r: &JObject) -> JResult<JObject> {
    let t = make_thread(r.clone(), Some(JString::from("")));
    start(&t)?;
    Ok(t)
}

// ------------------------------------------------------------------ ExecutorService / Future

pub struct JExecutor {
    base: ObjectBase,
    pool: i32,
    threads: i32,
    submitted: Cell<i32>,
    shutdown: Cell<bool>,
    pending: RefCell<Vec<JObject>>,
    workers: RefCell<Vec<JObject>>,
}

impl Object for JExecutor {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.concurrent.ThreadPoolExecutor"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.concurrent.ExecutorService" | "java.util.concurrent.Executor"
            | "java.util.concurrent.ThreadPoolExecutor" | "java.lang.AutoCloseable")
    }
}

#[derive(Clone)]
enum FState {
    Pending,
    Running,
    Done(JObject),
    Failed(JObject),
    Cancelled,
}

pub struct JFuture {
    base: ObjectBase,
    task: JObject,
    callable: bool,
    state: RefCell<FState>,
    executor: JObject,
    worker: JObject,
    /// execute() で投入したタスク（例外はスレッドの未捕捉例外として表示する）。
    report: bool,
}

impl Object for JFuture {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.concurrent.FutureTask"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.concurrent.Future" | "java.util.concurrent.FutureTask" | "java.lang.Runnable")
    }
}

fn new_pool(threads: i32) -> JResult<JObject> {
    if threads <= 0 {
        return throw("java.lang.IllegalArgumentException", None);
    }
    let pool = SCHED.with(|s| {
        let mut s = s.borrow_mut();
        s.pool_counter += 1;
        s.pool_counter
    });
    Ok(alloc(|base| JExecutor {
        base,
        pool,
        threads,
        submitted: Cell::new(0),
        shutdown: Cell::new(false),
        pending: RefCell::new(Vec::new()),
        workers: RefCell::new(Vec::new()),
    }))
}

/// `Executors.newFixedThreadPool(n)`。
pub fn new_fixed_thread_pool(n: i32) -> JResult<JObject> {
    new_pool(n)
}

/// `Executors.newSingleThreadExecutor()`。
pub fn new_single_thread_executor() -> JResult<JObject> {
    new_pool(1)
}

/// `Executors.newCachedThreadPool()` / `newVirtualThreadPerTaskExecutor()`（タスクごとに新しいスレッド）。
pub fn new_cached_thread_pool() -> JResult<JObject> {
    new_pool(i32::MAX)
}

fn executor(o: &JObject) -> JResult<&JExecutor> {
    match o.downcast_ref::<JExecutor>() {
        Some(e) => Ok(e),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.concurrent.ExecutorService")
        }
    }
}

fn enqueue(ex_obj: &JObject, task: &JObject, callable: bool, report: bool) -> JResult<JObject> {
    let ex = executor(ex_obj)?;
    task.obj()?;
    if ex.shutdown.get() {
        return throw("java.util.concurrent.RejectedExecutionException", Some("Task rejected: executor has been shut down"));
    }
    let i = ex.submitted.get();
    ex.submitted.set(i + 1);
    // タスクを割り当てるスレッド（固定サイズのプールは順番に、そうでなければタスクごとに新しいスレッド）。
    let slot = if ex.threads == i32::MAX { i } else { i % ex.threads };
    let worker = {
        let mut ws = ex.workers.borrow_mut();
        while ws.len() <= slot as usize {
            let n = ws.len() + 1;
            ws.push(make_thread(JObject::null(), Some(JString::from(format!("pool-{}-thread-{n}", ex.pool)))));
        }
        ws[slot as usize].clone()
    };
    let f = alloc(|base| JFuture {
        base,
        task: task.clone(),
        callable,
        state: RefCell::new(FState::Pending),
        executor: ex_obj.clone(),
        worker,
        report,
    });
    ex.pending.borrow_mut().push(f.clone());
    SCHED.with(|s| s.borrow_mut().queue.push_back(Job::Task(f.clone())));
    Ok(f)
}

/// `executor.submit(callable)` / `submit(runnable)`。
pub fn submit(ex: &JObject, task: &JObject, callable: bool) -> JResult<JObject> {
    enqueue(ex, task, callable, false)
}

/// `executor.execute(runnable)`。
pub fn execute(ex: &JObject, task: &JObject) -> JResult<()> {
    enqueue(ex, task, false, true)?;
    Ok(())
}

fn future(o: &JObject) -> JResult<&JFuture> {
    match o.downcast_ref::<JFuture>() {
        Some(f) => Ok(f),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.concurrent.Future")
        }
    }
}

fn run_task(fo: &JObject) {
    let Ok(f) = future(fo) else { return };
    if !matches!(*f.state.borrow(), FState::Pending) {
        return;
    }
    *f.state.borrow_mut() = FState::Running;
    SCHED.with(|s| s.borrow_mut().current.push(f.worker.clone()));
    let r = if f.callable { f.task.invoke("call", &[]) } else { f.task.invoke("run", &[]).map(|_| JObject::null()) };
    SCHED.with(|s| s.borrow_mut().current.pop());
    if let Ok(ex) = executor(&f.executor) {
        ex.pending.borrow_mut().retain(|x| !x.same(fo));
    }
    *f.state.borrow_mut() = match r {
        Ok(v) => FState::Done(v),
        Err(e) => {
            if f.report {
                let name = with_thread(&f.worker, |t| Ok(t.name.borrow().as_str_or_null().to_string())).unwrap_or_default();
                report_uncaught(&name, &e);
            }
            FState::Failed(e)
        }
    };
}

/// `future.get()`（未実行ならキューを順に実行する。例外は ExecutionException で包む）。
pub fn future_get(fo: &JObject) -> JResult<JObject> {
    loop {
        let st = future(fo)?.state.borrow().clone();
        match st {
            FState::Done(v) => return Ok(v),
            FState::Failed(e) => {
                let msg = crate::lang::stringify::to_java_string(&e)?;
                return Err(crate::lang::throwable::new_throwable("java.util.concurrent.ExecutionException", JString::from(msg), e));
            }
            FState::Cancelled => return throw("java.util.concurrent.CancellationException", None),
            FState::Running => return throw("java.lang.IllegalStateException", Some("deadlock: waiting for a running task")),
            FState::Pending => {
                if !run_one() {
                    run_task(fo);
                }
            }
        }
    }
}

/// `future.isDone()`。
pub fn future_is_done(fo: &JObject) -> JResult<bool> {
    Ok(matches!(*future(fo)?.state.borrow(), FState::Done(_) | FState::Failed(_) | FState::Cancelled))
}

/// `future.isCancelled()`。
pub fn future_is_cancelled(fo: &JObject) -> JResult<bool> {
    Ok(matches!(*future(fo)?.state.borrow(), FState::Cancelled))
}

/// `future.cancel(mayInterrupt)`。
pub fn future_cancel(fo: &JObject) -> JResult<bool> {
    let f = future(fo)?;
    if matches!(*f.state.borrow(), FState::Pending) {
        *f.state.borrow_mut() = FState::Cancelled;
        return Ok(true);
    }
    Ok(false)
}

/// `future.resultNow()`。
pub fn future_result_now(fo: &JObject) -> JResult<JObject> {
    match future(fo)?.state.borrow().clone() {
        FState::Done(v) => Ok(v),
        _ => throw("java.lang.IllegalStateException", Some("Task has not completed")),
    }
}

/// `executor.invokeAll(tasks)`。
pub fn invoke_all(ex: &JObject, tasks: &JObject) -> JResult<JObject> {
    let list = crate::util::collections::new_list(crate::util::collections::ListKind::ArrayList);
    let mut futures = Vec::new();
    for t in crate::util::collections::to_vec(tasks)? {
        futures.push(submit(ex, &t, true)?);
    }
    for f in futures {
        let _ = future_get(&f);
        crate::util::collections::Collections::add(&list, f)?;
    }
    Ok(list)
}

/// `executor.invokeAny(tasks)`（最初に成功したタスクの結果）。
pub fn invoke_any(ex: &JObject, tasks: &JObject) -> JResult<JObject> {
    let mut last = None;
    for t in crate::util::collections::to_vec(tasks)? {
        let f = submit(ex, &t, true)?;
        match future_get(&f) {
            Ok(v) => return Ok(v),
            Err(e) => last = Some(e),
        }
    }
    match last {
        Some(e) => Err(e),
        None => throw("java.lang.IllegalArgumentException", None),
    }
}

/// `executor.shutdown()`。
pub fn shutdown(ex: &JObject) -> JResult<()> {
    executor(ex)?.shutdown.set(true);
    Ok(())
}

/// `executor.shutdownNow()`（まだ実行していないタスクを取り消して返す）。
pub fn shutdown_now(ex: &JObject) -> JResult<JObject> {
    let e = executor(ex)?;
    e.shutdown.set(true);
    let pending: Vec<JObject> = e.pending.borrow_mut().drain(..).collect();
    let list = crate::util::collections::new_list(crate::util::collections::ListKind::ArrayList);
    for f in pending {
        if let Ok(fu) = future(&f) {
            *fu.state.borrow_mut() = FState::Cancelled;
            crate::util::collections::Collections::add(&list, fu.task.clone())?;
        }
    }
    Ok(list)
}

/// `executor.awaitTermination(timeout, unit)` / `close()`（残りのタスクを実行する）。
pub fn await_termination(ex: &JObject) -> JResult<bool> {
    loop {
        let pending = executor(ex)?.pending.borrow().first().cloned();
        match pending {
            Some(f) => {
                future_get(&f).ok();
            }
            None => return Ok(true),
        }
    }
}

/// `executor.close()`（Java 19 以降）。
pub fn close(ex: &JObject) -> JResult<()> {
    shutdown(ex)?;
    await_termination(ex)?;
    Ok(())
}

pub fn is_shutdown(ex: &JObject) -> JResult<bool> {
    Ok(executor(ex)?.shutdown.get())
}

pub fn is_terminated(ex: &JObject) -> JResult<bool> {
    let e = executor(ex)?;
    Ok(e.shutdown.get() && e.pending.borrow().is_empty())
}

// ------------------------------------------------------------------ CompletableFuture

pub struct JCompletable {
    base: ObjectBase,
    state: RefCell<FState>,
    /// 例外が非同期の段で起きたか（join / exceptionally で CompletionException に包む）。
    wrapped: Cell<bool>,
}

impl Object for JCompletable {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.concurrent.CompletableFuture"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.concurrent.CompletableFuture" | "java.util.concurrent.Future"
            | "java.util.concurrent.CompletionStage")
    }
    fn to_jstring(&self) -> JResult<JString> {
        let st = match &*self.state.borrow() {
            FState::Done(_) => "Completed normally".to_string(),
            FState::Failed(_) | FState::Cancelled => "Completed exceptionally".to_string(),
            _ => "Not completed".to_string(),
        };
        Ok(JString::from(format!("java.util.concurrent.CompletableFuture@{:x}[{st}]", self.base.identity_hash() as u32)))
    }
}

fn completable(state: FState, wrapped: bool) -> JObject {
    alloc(|base| JCompletable { base, state: RefCell::new(state), wrapped: Cell::new(wrapped) })
}

fn cf(o: &JObject) -> JResult<&JCompletable> {
    match o.downcast_ref::<JCompletable>() {
        Some(c) => Ok(c),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.concurrent.CompletableFuture")
        }
    }
}

fn completion_exception(e: &JObject) -> JResult<JObject> {
    if e.instance_of("java.util.concurrent.CompletionException") {
        return Ok(e.clone());
    }
    let msg = crate::lang::stringify::to_java_string(e)?;
    Ok(crate::lang::throwable::new_throwable("java.util.concurrent.CompletionException", JString::from(msg), e.clone()))
}

fn from_result(r: JResult<JObject>) -> JObject {
    match r {
        Ok(v) => completable(FState::Done(v), false),
        Err(e) => completable(FState::Failed(e), true),
    }
}

/// `CompletableFuture.supplyAsync(supplier)`（すぐに実行する。Java でも結果は同じ）。
pub fn supply_async(supplier: &JObject) -> JResult<JObject> {
    supplier.obj()?;
    run_pending();
    Ok(from_result(supplier.invoke("get", &[])))
}

/// `CompletableFuture.runAsync(runnable)`。
pub fn run_async(r: &JObject) -> JResult<JObject> {
    r.obj()?;
    run_pending();
    Ok(from_result(r.invoke("run", &[]).map(|_| JObject::null())))
}

/// `CompletableFuture.completedFuture(v)`。
pub fn completed_future(v: impl Into<JObject>) -> JObject {
    completable(FState::Done(v.into()), false)
}

/// `CompletableFuture.failedFuture(e)`。
pub fn failed_future(e: impl Into<JObject>) -> JObject {
    completable(FState::Failed(e.into()), false)
}

/// `new CompletableFuture<>()`。
pub fn new_completable() -> JObject {
    completable(FState::Pending, false)
}

/// `cf.complete(v)`。
pub fn complete(o: &JObject, v: impl Into<JObject>) -> JResult<bool> {
    let c = cf(o)?;
    if matches!(*c.state.borrow(), FState::Pending) {
        *c.state.borrow_mut() = FState::Done(v.into());
        return Ok(true);
    }
    Ok(false)
}

/// `cf.completeExceptionally(e)`。
pub fn complete_exceptionally(o: &JObject, e: impl Into<JObject>) -> JResult<bool> {
    let c = cf(o)?;
    if matches!(*c.state.borrow(), FState::Pending) {
        *c.state.borrow_mut() = FState::Failed(e.into());
        return Ok(true);
    }
    Ok(false)
}

fn then(o: &JObject, f: impl FnOnce(JObject) -> JResult<JObject>) -> JResult<JObject> {
    let c = cf(o)?;
    let st = c.state.borrow().clone();
    Ok(match st {
        FState::Done(v) => from_result(f(v)),
        FState::Failed(e) => completable(FState::Failed(e), true),
        other => completable(other, false),
    })
}

/// `cf.thenApply(f)`。
pub fn then_apply(o: &JObject, f: &JObject) -> JResult<JObject> {
    let f = f.clone();
    then(o, move |v| f.invoke("apply", &[v]))
}

/// `cf.thenAccept(c)`。
pub fn then_accept(o: &JObject, f: &JObject) -> JResult<JObject> {
    let f = f.clone();
    then(o, move |v| f.invoke("accept", &[v]).map(|_| JObject::null()))
}

/// `cf.thenRun(r)`。
pub fn then_run(o: &JObject, f: &JObject) -> JResult<JObject> {
    let f = f.clone();
    then(o, move |_| f.invoke("run", &[]).map(|_| JObject::null()))
}

/// `cf.thenCompose(f)`。
pub fn then_compose(o: &JObject, f: &JObject) -> JResult<JObject> {
    let f = f.clone();
    let c = cf(o)?;
    let st = c.state.borrow().clone();
    Ok(match st {
        FState::Done(v) => match f.invoke("apply", &[v]) {
            Ok(next) => next,
            Err(e) => completable(FState::Failed(e), true),
        },
        FState::Failed(e) => completable(FState::Failed(e), true),
        other => completable(other, false),
    })
}

/// `cf.thenCombine(other, f)`。
pub fn then_combine(o: &JObject, other: &JObject, f: &JObject) -> JResult<JObject> {
    let (a, b) = (cf(o)?.state.borrow().clone(), cf(other)?.state.borrow().clone());
    Ok(match (a, b) {
        (FState::Done(x), FState::Done(y)) => from_result(f.invoke("apply", &[x, y])),
        (FState::Failed(e), _) | (_, FState::Failed(e)) => completable(FState::Failed(e), true),
        _ => completable(FState::Pending, false),
    })
}

/// `cf.exceptionally(f)`。
pub fn exceptionally(o: &JObject, f: &JObject) -> JResult<JObject> {
    let c = cf(o)?;
    let st = c.state.borrow().clone();
    Ok(match st {
        FState::Failed(e) => {
            let arg = if c.wrapped.get() { completion_exception(&e)? } else { e };
            from_result(f.invoke("apply", &[arg]))
        }
        other => completable(other, false),
    })
}

/// `cf.handle(f)`（f(result, exception)）。
pub fn handle(o: &JObject, f: &JObject) -> JResult<JObject> {
    let c = cf(o)?;
    let st = c.state.borrow().clone();
    Ok(match st {
        FState::Done(v) => from_result(f.invoke("apply", &[v, JObject::null()])),
        FState::Failed(e) => {
            let arg = if c.wrapped.get() { completion_exception(&e)? } else { e };
            from_result(f.invoke("apply", &[JObject::null(), arg]))
        }
        other => completable(other, false),
    })
}

/// `cf.whenComplete(action)`。
pub fn when_complete(o: &JObject, f: &JObject) -> JResult<JObject> {
    let c = cf(o)?;
    let st = c.state.borrow().clone();
    match &st {
        FState::Done(v) => {
            f.invoke("accept", &[v.clone(), JObject::null()])?;
        }
        FState::Failed(e) => {
            let arg = if c.wrapped.get() { completion_exception(e)? } else { e.clone() };
            f.invoke("accept", &[JObject::null(), arg])?;
        }
        _ => {}
    }
    Ok(o.clone())
}

/// `cf.join()`（例外は CompletionException）。
pub fn cf_join(o: &JObject) -> JResult<JObject> {
    match cf(o)?.state.borrow().clone() {
        FState::Done(v) => Ok(v),
        FState::Failed(e) => Err(completion_exception(&e)?),
        FState::Cancelled => throw("java.util.concurrent.CancellationException", None),
        _ => throw("java.lang.IllegalStateException", Some("CompletableFuture is not completed (deadlock)")),
    }
}

/// `cf.get()`（例外は ExecutionException）。
pub fn cf_get(o: &JObject) -> JResult<JObject> {
    match cf(o)?.state.borrow().clone() {
        FState::Done(v) => Ok(v),
        FState::Failed(e) => {
            let cause = if e.instance_of("java.util.concurrent.CompletionException") {
                crate::lang::throwable::get_cause(&e)?
            } else {
                e
            };
            let msg = crate::lang::stringify::to_java_string(&cause)?;
            Err(crate::lang::throwable::new_throwable("java.util.concurrent.ExecutionException", JString::from(msg), cause))
        }
        FState::Cancelled => throw("java.util.concurrent.CancellationException", None),
        _ => throw("java.lang.IllegalStateException", Some("CompletableFuture is not completed (deadlock)")),
    }
}

/// `cf.getNow(default)`。
pub fn cf_get_now(o: &JObject, dflt: impl Into<JObject>) -> JResult<JObject> {
    match cf(o)?.state.borrow().clone() {
        FState::Pending | FState::Running => Ok(dflt.into()),
        _ => cf_join(o),
    }
}

/// `cf.isDone()`。
pub fn cf_is_done(o: &JObject) -> JResult<bool> {
    Ok(!matches!(*cf(o)?.state.borrow(), FState::Pending | FState::Running))
}

/// `cf.isCompletedExceptionally()`。
pub fn cf_is_completed_exceptionally(o: &JObject) -> JResult<bool> {
    Ok(matches!(*cf(o)?.state.borrow(), FState::Failed(_) | FState::Cancelled))
}

/// `CompletableFuture.allOf(cfs...)`。
pub fn all_of(cfs: &crate::JArray<JObject>) -> JResult<JObject> {
    for c in cfs.to_vec()? {
        if let FState::Failed(e) = cf(&c)?.state.borrow().clone() {
            return Ok(completable(FState::Failed(e), true));
        }
    }
    Ok(completable(FState::Done(JObject::null()), false))
}

/// `CompletableFuture.anyOf(cfs...)`。
pub fn any_of(cfs: &crate::JArray<JObject>) -> JResult<JObject> {
    for c in cfs.to_vec()? {
        match cf(&c)?.state.borrow().clone() {
            FState::Done(v) => return Ok(completable(FState::Done(v), false)),
            FState::Failed(e) => return Ok(completable(FState::Failed(e), true)),
            _ => {}
        }
    }
    Ok(completable(FState::Pending, false))
}

// ------------------------------------------------------------------ CountDownLatch / Semaphore

pub struct JLatch {
    base: ObjectBase,
    count: Cell<i64>,
    permits: bool,
}

impl Object for JLatch {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        if self.permits { "java.util.concurrent.Semaphore" } else { "java.util.concurrent.CountDownLatch" }
    }
    fn to_jstring(&self) -> JResult<JString> {
        let what = if self.permits { "Permits" } else { "Count" };
        Ok(JString::from(format!("{}@{:x}[{what} = {}]", self.class_name(), self.base.identity_hash() as u32, self.count.get())))
    }
}

fn latch(o: &JObject) -> JResult<&JLatch> {
    match o.downcast_ref::<JLatch>() {
        Some(l) => Ok(l),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.concurrent.CountDownLatch")
        }
    }
}

/// `new CountDownLatch(n)`。
pub fn new_latch(n: i32) -> JResult<JObject> {
    if n < 0 {
        return throw("java.lang.IllegalArgumentException", Some("count < 0"));
    }
    Ok(alloc(|base| JLatch { base, count: Cell::new(n as i64), permits: false }))
}

/// `new Semaphore(permits)`。
pub fn new_semaphore(n: i32) -> JObject {
    alloc(|base| JLatch { base, count: Cell::new(n as i64), permits: true })
}

pub fn count_down(o: &JObject) -> JResult<()> {
    let l = latch(o)?;
    if l.count.get() > 0 {
        l.count.set(l.count.get() - 1);
    }
    Ok(())
}

pub fn get_count(o: &JObject) -> JResult<i64> {
    Ok(latch(o)?.count.get())
}

/// `latch.await()`: 0 になるまで、キューを順に実行する。
pub fn latch_await(o: &JObject) -> JResult<bool> {
    while latch(o)?.count.get() > 0 {
        if !run_one() {
            return Ok(false);
        }
    }
    Ok(true)
}

/// `semaphore.acquire(n)`（許可がなければほかのスレッドを実行する）。
pub fn acquire(o: &JObject, n: i32) -> JResult<()> {
    let l = latch(o)?;
    while l.count.get() < n as i64 {
        if !run_one() {
            break;
        }
    }
    l.count.set(l.count.get() - n as i64);
    Ok(())
}

/// `semaphore.tryAcquire(n)`。
pub fn try_acquire(o: &JObject, n: i32) -> JResult<bool> {
    let l = latch(o)?;
    if l.count.get() >= n as i64 {
        l.count.set(l.count.get() - n as i64);
        return Ok(true);
    }
    Ok(false)
}

/// `semaphore.release(n)`。
pub fn release(o: &JObject, n: i32) -> JResult<()> {
    let l = latch(o)?;
    l.count.set(l.count.get() + n as i64);
    Ok(())
}

// ------------------------------------------------------------------ atomic

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum AtomicKind {
    Int,
    Long,
    Boolean,
    Reference,
    LongAdder,
}

pub struct JAtomic {
    base: ObjectBase,
    kind: AtomicKind,
    value: RefCell<JObject>,
}

impl Object for JAtomic {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        match self.kind {
            AtomicKind::Int => "java.util.concurrent.atomic.AtomicInteger",
            AtomicKind::Long => "java.util.concurrent.atomic.AtomicLong",
            AtomicKind::Boolean => "java.util.concurrent.atomic.AtomicBoolean",
            AtomicKind::Reference => "java.util.concurrent.atomic.AtomicReference",
            AtomicKind::LongAdder => "java.util.concurrent.atomic.LongAdder",
        }
    }
    fn instance_of(&self, class: &str) -> bool {
        class == "java.lang.Object" || class == self.class_name()
            || (matches!(self.kind, AtomicKind::Int | AtomicKind::Long | AtomicKind::LongAdder) && class == "java.lang.Number")
    }
    fn to_jstring(&self) -> JResult<JString> {
        crate::lang::string::JString::value_of_object(&self.value.borrow())
    }
}

/// `new AtomicInteger(v)` など（値はボクシング済み）。
pub fn new_atomic(kind: AtomicKind, v: impl Into<JObject>) -> JObject {
    alloc(|base| JAtomic { base, kind, value: RefCell::new(v.into()) })
}

fn atomic(o: &JObject) -> JResult<&JAtomic> {
    match o.downcast_ref::<JAtomic>() {
        Some(a) => Ok(a),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.concurrent.atomic.AtomicInteger")
        }
    }
}

/// `get()`（ボクシングして返す）。
pub fn atomic_get(o: &JObject) -> JResult<JObject> {
    Ok(atomic(o)?.value.borrow().clone())
}

/// `set(v)`。
pub fn atomic_set(o: &JObject, v: impl Into<JObject>) -> JResult<()> {
    *atomic(o)?.value.borrow_mut() = v.into();
    Ok(())
}

/// `getAndSet(v)`。
pub fn atomic_get_and_set(o: &JObject, v: impl Into<JObject>) -> JResult<JObject> {
    Ok(std::mem::replace(&mut *atomic(o)?.value.borrow_mut(), v.into()))
}

/// 整数の加算（AtomicInteger / AtomicLong / LongAdder）。戻り値は (前の値, 後の値)。
pub fn atomic_add(o: &JObject, delta: i64) -> JResult<(i64, i64)> {
    let a = atomic(o)?;
    let old = crate::lang::boxed::number_i64(&a.value.borrow())?;
    let new = match a.kind {
        AtomicKind::Int => (old as i32).wrapping_add(delta as i32) as i64,
        _ => old.wrapping_add(delta),
    };
    *a.value.borrow_mut() = if a.kind == AtomicKind::Int { crate::box_i32(new as i32) } else { crate::box_i64(new) };
    Ok((old, new))
}

/// `compareAndSet(expect, update)`（参照は ==、数値・真偽値は値で比べる）。
pub fn compare_and_set(o: &JObject, expect: impl Into<JObject>, update: impl Into<JObject>) -> JResult<bool> {
    let a = atomic(o)?;
    let expect = expect.into();
    let same = {
        let cur = a.value.borrow();
        if a.kind == AtomicKind::Reference { cur.same(&expect) } else { crate::object::equals_nullable(&cur, &expect)? }
    };
    if same {
        *a.value.borrow_mut() = update.into();
    }
    Ok(same)
}

/// `updateAndGet(f)` / `getAndUpdate(f)`（after なら更新後の値を返す）。
pub fn atomic_update(o: &JObject, f: &JObject, after: bool) -> JResult<JObject> {
    let a = atomic(o)?;
    let old = a.value.borrow().clone();
    let method = match a.kind {
        AtomicKind::Int => "applyAsInt",
        AtomicKind::Long => "applyAsLong",
        _ => "apply",
    };
    let new = f.invoke(method, &[old.clone()])?;
    *a.value.borrow_mut() = new.clone();
    Ok(if after { new } else { old })
}

/// `accumulateAndGet(x, f)` / `getAndAccumulate(x, f)`。
pub fn atomic_accumulate(o: &JObject, x: impl Into<JObject>, f: &JObject, after: bool) -> JResult<JObject> {
    let a = atomic(o)?;
    let old = a.value.borrow().clone();
    let method = match a.kind {
        AtomicKind::Int => "applyAsInt",
        AtomicKind::Long => "applyAsLong",
        _ => "apply",
    };
    let new = f.invoke(method, &[old.clone(), x.into()])?;
    *a.value.borrow_mut() = new.clone();
    Ok(if after { new } else { old })
}

// ------------------------------------------------------------------ ThreadLocal

pub struct JThreadLocal {
    base: ObjectBase,
    initial: JObject,
    values: RefCell<Vec<(JObject, JObject)>>,
}

impl Object for JThreadLocal {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.lang.ThreadLocal"
    }
}

/// `new ThreadLocal<>()` / `ThreadLocal.withInitial(supplier)`（supplier は null 可）。
pub fn new_thread_local(initial: &JObject) -> JObject {
    alloc(|base| JThreadLocal { base, initial: initial.clone(), values: RefCell::new(Vec::new()) })
}

fn tl(o: &JObject) -> JResult<&JThreadLocal> {
    match o.downcast_ref::<JThreadLocal>() {
        Some(t) => Ok(t),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.lang.ThreadLocal")
        }
    }
}

pub fn thread_local_get(o: &JObject) -> JResult<JObject> {
    let t = tl(o)?;
    let cur = current_thread();
    if let Some((_, v)) = t.values.borrow().iter().find(|(k, _)| k.same(&cur)) {
        return Ok(v.clone());
    }
    let v = if t.initial.is_null() { JObject::null() } else { t.initial.invoke("get", &[])? };
    t.values.borrow_mut().push((cur, v.clone()));
    Ok(v)
}

pub fn thread_local_set(o: &JObject, v: impl Into<JObject>) -> JResult<()> {
    let t = tl(o)?;
    let cur = current_thread();
    let v = v.into();
    let mut vals = t.values.borrow_mut();
    match vals.iter_mut().find(|(k, _)| k.same(&cur)) {
        Some(e) => e.1 = v,
        None => vals.push((cur, v)),
    }
    Ok(())
}

pub fn thread_local_remove(o: &JObject) -> JResult<()> {
    let t = tl(o)?;
    let cur = current_thread();
    t.values.borrow_mut().retain(|(k, _)| !k.same(&cur));
    Ok(())
}

// ------------------------------------------------------------------ Lock / Condition

pub struct JLock {
    base: ObjectBase,
    holds: Cell<i32>,
}

impl Object for JLock {
    fn base(&self) -> &ObjectBase {
        &self.base
    }
    fn class_name(&self) -> &'static str {
        "java.util.concurrent.locks.ReentrantLock"
    }
    fn instance_of(&self, class: &str) -> bool {
        matches!(class, "java.lang.Object" | "java.util.concurrent.locks.Lock" | "java.util.concurrent.locks.ReentrantLock"
            | "java.util.concurrent.locks.ReadWriteLock" | "java.util.concurrent.locks.Condition")
    }
}

/// `new ReentrantLock()` / `new ReentrantReadWriteLock()` / `lock.newCondition()`。
pub fn new_lock() -> JObject {
    alloc(|base| JLock { base, holds: Cell::new(0) })
}

fn lock_of(o: &JObject) -> JResult<&JLock> {
    match o.downcast_ref::<JLock>() {
        Some(l) => Ok(l),
        None => {
            o.obj()?;
            crate::object::class_cast(o, "java.util.concurrent.locks.Lock")
        }
    }
}

pub fn lock(o: &JObject) -> JResult<()> {
    let l = lock_of(o)?;
    l.holds.set(l.holds.get() + 1);
    Ok(())
}

pub fn unlock(o: &JObject) -> JResult<()> {
    let l = lock_of(o)?;
    if l.holds.get() == 0 {
        return throw("java.lang.IllegalMonitorStateException", None);
    }
    l.holds.set(l.holds.get() - 1);
    Ok(())
}

/// `condition.await()` / `object.wait()`（ほかのスレッドを実行する）。
pub fn wait() -> JResult<()> {
    run_pending();
    Ok(())
}

/// `TimeUnit.X` の値（時間は使わないので名前だけを持つ）。
pub fn time_unit(name: &'static str) -> JObject {
    JObject::from(JString::from_static(name))
}

/// `TimeUnit.X.toMillis(n)` など（ミリ秒への換算。name は単位の名前）。
pub fn to_millis(unit: &JObject, n: i64) -> JResult<i64> {
    let u = unit.cast_string()?;
    Ok(match u.as_str()? {
        "NANOSECONDS" => n / 1_000_000,
        "MICROSECONDS" => n / 1_000,
        "MILLISECONDS" => n,
        "SECONDS" => n.wrapping_mul(1_000),
        "MINUTES" => n.wrapping_mul(60_000),
        "HOURS" => n.wrapping_mul(3_600_000),
        _ => n.wrapping_mul(86_400_000),
    })
}

/// 実行中のジョブ（テスト用）。
pub fn pending_count() -> usize {
    SCHED.with(|s| s.borrow().queue.len())
}

impl JExecutor {
    /// 実行待ちのタスクの数。
    pub fn pending(&self) -> usize {
        self.pending.borrow().len()
    }
}

/// AtomicInteger の加算（after なら加算後の値を返す）。
pub fn atomic_int(o: &JObject, delta: i32, after: bool) -> JResult<i32> {
    let (old, new) = atomic_add(o, delta as i64)?;
    Ok(if after { new as i32 } else { old as i32 })
}

/// AtomicLong / LongAdder の加算（after なら加算後の値を返す）。
pub fn atomic_long(o: &JObject, delta: i64, after: bool) -> JResult<i64> {
    let (old, new) = atomic_add(o, delta)?;
    Ok(if after { new } else { old })
}

/// `lock.tryLock()`（1 つの OS スレッドで順に実行するので、常に取れる）。
pub fn try_lock(o: &JObject) -> JResult<bool> {
    lock(o)?;
    Ok(true)
}

/// `lock.isLocked()`。
pub fn is_locked(o: &JObject) -> JResult<bool> {
    Ok(lock_of(o)?.holds.get() > 0)
}

/// `object.notify()` / `notifyAll()` / `condition.signal()`（待っているスレッドは wait() で実行済み）。
pub fn notify(o: &JObject) -> JResult<()> {
    o.obj()?;
    Ok(())
}

/// `TimeUnit.X.toSeconds(n)` など（target への換算）。
pub fn convert_unit(unit: &JObject, n: i64, target: &str) -> JResult<i64> {
    let ms = to_millis(unit, n)?;
    Ok(match target {
        "SECONDS" => ms / 1_000,
        "MINUTES" => ms / 60_000,
        _ => ms,
    })
}
