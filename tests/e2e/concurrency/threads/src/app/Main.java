package app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/** スレッドと java.util.concurrent（結果が実行順に依存しないプログラム）。 */
public class Main {
    static int shared;

    static synchronized void bump() {
        shared++;
    }

    public static void main(String[] args) throws Exception {
        // Thread + join
        AtomicInteger counter = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                for (int k = 0; k <= n * 10; k++) {
                    counter.incrementAndGet();
                    bump();
                }
            }, "worker-" + i);
            threads.add(t);
        }
        System.out.println("alive before start: " + threads.get(0).isAlive());
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        System.out.println("counter = " + counter.get() + ", shared = " + shared);
        System.out.println("names: " + threads.get(0).getName() + ", " + threads.get(3).getName());
        System.out.println("alive after join: " + threads.get(0).isAlive());
        System.out.println("main thread: " + Thread.currentThread().getName());

        // Thread 名の既定と currentThread
        AtomicReference<String> seen = new AtomicReference<>();
        Thread named = new Thread(() -> seen.set(Thread.currentThread().getName()));
        named.start();
        named.join();
        System.out.println("inside: " + seen.get().startsWith("Thread-"));

        // ExecutorService
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final int n = i;
            futures.add(pool.submit(() -> n * n));
        }
        int sum = 0;
        for (Future<Integer> f : futures) {
            sum += f.get();
        }
        System.out.println("sum of squares = " + sum);
        Future<String> failing = pool.submit((Callable<String>) () -> {
            throw new IllegalStateException("boom");
        });
        try {
            failing.get();
        } catch (ExecutionException e) {
            System.out.println("caught: " + e.getCause().getClass().getSimpleName() + " / " + e.getCause().getMessage());
        }
        List<Callable<String>> tasks = List.of(() -> "a", () -> "b", () -> "c");
        StringBuilder sb = new StringBuilder();
        for (Future<String> f : pool.invokeAll(tasks)) {
            sb.append(f.get());
        }
        System.out.println("invokeAll: " + sb);
        pool.shutdown();
        System.out.println("terminated: " + pool.awaitTermination(1, TimeUnit.SECONDS) + " " + pool.isShutdown());

        // CountDownLatch
        CountDownLatch latch = new CountDownLatch(3);
        LongAdder adder = new LongAdder();
        ExecutorService single = Executors.newSingleThreadExecutor();
        for (int i = 0; i < 3; i++) {
            final int n = i;
            single.execute(() -> {
                adder.add(n + 1);
                latch.countDown();
            });
        }
        latch.await();
        System.out.println("latch count = " + latch.getCount() + ", adder = " + adder.sum());
        single.shutdown();

        // CompletableFuture
        CompletableFuture<Integer> cf = CompletableFuture.supplyAsync(() -> 20)
                .thenApply(x -> x + 1)
                .thenApply(x -> x * 2);
        System.out.println("cf = " + cf.join());
        CompletableFuture<String> combined = cf.thenCombine(CompletableFuture.completedFuture("!"), (a, b) -> a + b);
        System.out.println("combined = " + combined.get());
        CompletableFuture<Integer> bad = CompletableFuture.supplyAsync(() -> {
            if (true) {
                throw new ArithmeticException("div");
            }
            return 1;
        });
        System.out.println("recovered = " + bad.exceptionally(e -> -1).join());
        try {
            bad.join();
        } catch (RuntimeException e) {
            System.out.println("join: " + e.getClass().getName() + ": " + e.getMessage());
        }
        String handled = bad.handle((v, e) -> e == null ? "ok " + v : "failed " + e.getCause().getMessage()).join();
        System.out.println(handled);

        // atomics
        AtomicLong al = new AtomicLong(10);
        al.addAndGet(5);
        System.out.println("al = " + al.getAndIncrement() + " -> " + al.get());
        System.out.println("cas = " + al.compareAndSet(16, 100) + " " + al.compareAndSet(16, 0) + " " + al);
        AtomicInteger ai = new AtomicInteger(3);
        System.out.println("update = " + ai.updateAndGet(x -> x * 7) + ", accumulate = " + ai.accumulateAndGet(4, Math::max));

        // lock
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        try {
            System.out.println("locked = " + lock.isLocked());
        } finally {
            lock.unlock();
        }
        System.out.println("locked = " + lock.isLocked());

        // ThreadLocal
        ThreadLocal<Integer> local = ThreadLocal.withInitial(() -> 7);
        local.set(8);
        AtomicInteger other = new AtomicInteger();
        Thread t2 = new Thread(() -> other.set(local.get()));
        t2.start();
        t2.join();
        System.out.println("thread local: main=" + local.get() + " other=" + other.get());

        // 未捕捉例外（スレッドは終わり、main は続く）
        Thread crash = new Thread(() -> {
            throw new RuntimeException("in thread");
        }, "crasher");
        crash.start();
        crash.join();
        System.out.println("after crash");
        Thread.sleep(1);
        System.out.println("done");
    }
}
