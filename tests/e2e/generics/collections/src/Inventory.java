import java.util.*;

public class Inventory {
    static class Box<T> {
        private T value;

        Box(T value) {
            this.value = value;
        }

        T get() {
            return value;
        }

        void set(T v) {
            value = v;
        }

        <R> Box<R> map(java.util.function.Function<T, R> f) {
            return new Box<>(f.apply(value));
        }
    }

    static class Pair<A, B> {
        final A first;
        final B second;

        Pair(A first, B second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public String toString() {
            return "(" + first + ", " + second + ")";
        }
    }

    static <T extends Comparable<T>> T maxOf(List<T> items) {
        T best = items.get(0);
        for (T t : items) {
            if (t.compareTo(best) > 0) best = t;
        }
        return best;
    }

    static int sum(List<Integer> xs) {
        int s = 0;
        for (int x : xs) s += x;
        return s;
    }

    public static void main(String[] args) {
        List<String> words = new ArrayList<>(List.of("pear", "apple", "fig", "banana", "cherry", "apple"));
        words.add("date");
        words.remove("fig");
        System.out.println(words + " size=" + words.size() + " idx=" + words.indexOf("banana") + " has=" + words.contains("kiwi"));
        Collections.sort(words);
        System.out.println(words);
        words.sort(Comparator.comparing(String::length).thenComparing(Comparator.reverseOrder()));
        System.out.println(words);

        Map<String, Integer> counts = new HashMap<>();
        for (String w : "to be or not to be that is the question to be".split(" ")) {
            counts.put(w, counts.getOrDefault(w, 0) + 1);
        }
        System.out.println(counts);
        Map<String, Integer> sorted = new TreeMap<>(counts);
        System.out.println(sorted + " first=" + ((TreeMap<String, Integer>) sorted).firstKey());
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            if (e.getValue() > 1) System.out.print(e.getKey() + ":" + e.getValue() + " ");
        }
        System.out.println();
        Map<Integer, List<String>> byLen = new TreeMap<>();
        for (String w : words) {
            byLen.computeIfAbsent(w.length(), k -> new ArrayList<>()).add(w);
        }
        System.out.println(byLen);

        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < 30; i += 3) set.add(i % 7);
        System.out.println(set + " " + set.contains(3) + " " + new TreeSet<>(set));
    
        Deque<Integer> stack = new ArrayDeque<>();
        Queue<Integer> queue = new LinkedList<>();
        for (int i = 1; i <= 5; i++) {
            stack.push(i);
            queue.offer(i * 10);
        }
        System.out.println(stack.pop() + " " + stack.peek() + " " + queue.poll() + " " + queue.peek() + " " + stack + " " + queue);
        PriorityQueue<Integer> pq = new PriorityQueue<>(Comparator.reverseOrder());
        pq.addAll(List.of(5, 1, 9, 3, 7));
        StringBuilder order = new StringBuilder();
        while (!pq.isEmpty()) order.append(pq.poll()).append(' ');
        System.out.println(order.toString().trim());

        Box<Integer> b = new Box<>(21);
        Box<String> s = b.map(x -> "v" + (x * 2));
        b.set(b.get() + 1);
        System.out.println(b.get() + " " + s.get());
        Pair<String, List<Integer>> pair = new Pair<>("nums", Arrays.asList(3, 1, 2));
        System.out.println(pair + " max=" + maxOf(pair.second) + " sum=" + sum(pair.second) + " maxWord=" + maxOf(words));

        List<Integer> nums = new ArrayList<>();
        for (int i = 0; i < 10; i++) nums.add(i * i % 11);
        nums.removeIf(n -> n % 2 == 0);
        Iterator<Integer> it = nums.iterator();
        while (it.hasNext()) {
            if (it.next() == 9) it.remove();
        }
        System.out.println(nums + " " + Collections.max(nums) + " " + Collections.min(nums) + " " + Collections.frequency(nums, 5));
        Integer big1 = 1000, big2 = 1000;
        Integer small1 = 100, small2 = 100;
        System.out.println(big1.equals(big2) + " " + (small1 == small2) + " " + (big1 + big2));
        List<int[]> arrays = new ArrayList<>();
        arrays.add(new int[]{1, 2});
        System.out.println(arrays.get(0)[1] + " " + arrays.size());
    }
}
