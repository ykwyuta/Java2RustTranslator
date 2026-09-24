package app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** JDK のクラス（Thread・コレクション）を継承したクラス。 */
public class Main {
    static final StringBuilder LOG = new StringBuilder();

    static class Worker extends Thread {
        private final int id;
        int result;

        Worker(int id) {
            super("worker-" + id);
            this.id = id;
        }

        @Override
        public void run() {
            for (int i = 1; i <= id; i++) {
                result += i;
            }
            LOG.append(getName()).append(' ');
        }
    }

    /** 追加した回数を数えるリスト。 */
    static class CountingList<E> extends ArrayList<E> {
        int adds;

        @Override
        public boolean add(E e) {
            adds++;
            return super.add(e);
        }

        E last() {
            return get(size() - 1);
        }
    }

    /** 追加をログに出し、size を 100 倍にするリスト（JDK の型の変数を通しても上書きが呼ばれる）。 */
    static class Logging extends ArrayList<String> {
        @Override
        public boolean add(String s) {
            System.out.println("add " + s);
            return super.add(s);
        }

        @Override
        public int size() {
            return super.size() * 100;
        }
    }

    static class Counter extends HashMap<String, Integer> {
        void count(String word) {
            merge(word, 1, Integer::sum);
        }

        @Override
        public String toString() {
            return "Counter" + super.toString();
        }
    }

    static class Sorted extends TreeSet<String> {
        Sorted(List<String> items) {
            super(items);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        List<Worker> workers = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            workers.add(new Worker(i * 10));
        }
        for (Worker w : workers) {
            w.start();
        }
        for (Worker w : workers) {
            w.join();
        }
        for (Worker w : workers) {
            System.out.println(w.getName() + " -> " + w.result + " alive=" + w.isAlive());
        }
        System.out.println("log: " + LOG.toString().trim());

        Thread anon = new Thread() {
            @Override
            public void run() {
                System.out.println("anonymous thread " + (Thread.currentThread() == this));
            }
        };
        anon.start();
        anon.join();

        Thread withTarget = new Thread(() -> System.out.println("runnable target"));
        withTarget.run();

        CountingList<String> list = new CountingList<>();
        list.add("a");
        list.add("b");
        list.add("c");
        list.remove("b");
        System.out.println(list + " size=" + list.size() + " adds=" + list.adds + " last=" + list.last());
        System.out.println("contains a: " + list.contains("a") + ", equals: " + list.equals(List.of("a", "c")));
        int total = 0;
        for (String s : list) {
            total += s.length();
        }
        System.out.println("total length = " + total);

        List<String> logging = new Logging();
        logging.add("x");
        java.util.Collection<String> coll = logging;
        coll.add("y");
        System.out.println(logging.size() + " " + logging + " isEmpty=" + logging.isEmpty());

        Counter counter = new Counter();
        for (String w : "the cat and the hat and the bat".split(" ")) {
            counter.count(w);
        }
        System.out.println(counter);
        System.out.println("the=" + counter.get("the") + " size=" + counter.size());
        Map<String, Integer> asMap = counter;
        System.out.println("keys: " + new TreeSet<>(asMap.keySet()));

        Sorted sorted = new Sorted(List.of("pear", "apple", "fig"));
        System.out.println(sorted.first() + " .. " + sorted.last() + " " + sorted);
    }
}
