import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class MoreCollections {
    enum Day { MON, TUE, WED, THU, FRI, SAT, SUN }

    public static void main(String[] args) {
        Hashtable<String, Integer> table = new Hashtable<>();
        String[] words = {"alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa", "lambda", "mu"};
        for (int i = 0; i < words.length; i++) {
            table.put(words[i], i);
        }
        System.out.println(table);
        table.remove("gamma");
        StringBuilder keys = new StringBuilder();
        for (Enumeration<String> e = table.keys(); e.hasMoreElements(); ) {
            keys.append(e.nextElement()).append(' ');
        }
        System.out.println(keys.toString().trim() + " size=" + table.size() + " contains=" + table.contains(9));
        try {
            table.put("x", null);
        } catch (NullPointerException e) {
            System.out.println("Hashtable rejects null");
        }

        EnumMap<Day, String> plan = new EnumMap<>(Day.class);
        plan.put(Day.FRI, "party");
        plan.put(Day.MON, "work");
        plan.put(Day.WED, "gym");
        System.out.println(plan + " " + plan.containsKey(Day.TUE));
        EnumSet<Day> weekend = EnumSet.of(Day.SAT, Day.SUN);
        System.out.println(weekend + " " + EnumSet.complementOf(weekend) + " " + EnumSet.range(Day.TUE, Day.THU));
        System.out.println(EnumSet.allOf(Day.class).size() + " " + EnumSet.noneOf(Day.class) + " " + EnumSet.of(Day.WED, Day.MON, Day.FRI));

        Vector<Integer> v = new Vector<>(List.of(3, 1, 2));
        v.addElement(9);
        v.insertElementAt(7, 0);
        System.out.println(v + " " + v.firstElement() + " " + v.lastElement() + " " + v.elementAt(2));
        Stack<String> stack = new Stack<>();
        stack.push("a");
        stack.push("b");
        stack.push("c");
        System.out.println(stack.search("a") + " " + stack.search("c") + " " + stack.search("z") + " " + stack);

        List<String> list = new ArrayList<>(List.of("a", "b", "c", "d"));
        ListIterator<String> it = list.listIterator();
        while (it.hasNext()) {
            int idx = it.nextIndex();
            String s = it.next();
            if (s.equals("b")) {
                it.set("B");
            } else if (s.equals("c")) {
                it.remove();
            } else if (idx == 0) {
                it.add("a2");
            }
        }
        System.out.println(list);
        StringBuilder back = new StringBuilder();
        ListIterator<String> rev = list.listIterator(list.size());
        while (rev.hasPrevious()) {
            back.append(rev.previous());
        }
        System.out.println(back);

        Deque<Integer> dq = new ArrayDeque<>(List.of(1, 2, 3));
        Iterator<Integer> di = dq.descendingIterator();
        StringBuilder ds = new StringBuilder();
        di.forEachRemaining(x -> ds.append(x));
        System.out.println(ds);
        TreeSet<Integer> ts = new TreeSet<>(List.of(5, 1, 9, 3));
        System.out.println(ts.descendingSet() + " " + ts.headSet(5) + " " + ts.tailSet(5, false));
        TreeMap<String, Integer> tm = new TreeMap<>(Map.of("b", 2, "a", 1, "c", 3));
        NavigableMap<String, Integer> desc = tm.descendingMap();
        System.out.println(desc + " " + tm.firstKey() + " " + tm.floorKey("bb"));

        Object[] arr = list.toArray();
        String[] sarr = list.toArray(new String[0]);
        System.out.println(arr.length + " " + Arrays.toString(sarr));

        BitSet bits = new BitSet();
        bits.set(1);
        bits.set(5, 8);
        bits.clear(6);
        BitSet other = new BitSet();
        other.set(5);
        other.set(10);
        System.out.println(bits + " card=" + bits.cardinality() + " len=" + bits.length() + " next=" + bits.nextSetBit(2) + " clear=" + bits.nextClearBit(5));
        bits.or(other);
        System.out.println(bits + " " + bits.get(10) + " " + bits.intersects(other));

        List<Integer> nums = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        Collections.rotate(nums, 2);
        System.out.println(nums + " " + Collections.disjoint(nums, List.of(7, 8)) + " " + Collections.singleton(1) + " " + Collections.singletonMap("k", "v"));
        Collections.fill(nums, 0);
        System.out.println(nums + " " + Map.ofEntries(Map.entry("x", 1)));

        ConcurrentHashMap<String, Integer> chm = new ConcurrentHashMap<>();
        for (String w : words) {
            chm.merge(w.substring(0, 1), 1, Integer::sum);
        }
        System.out.println(chm);
        CopyOnWriteArrayList<String> cow = new CopyOnWriteArrayList<>(List.of("x", "y"));
        cow.add("z");
        LinkedBlockingQueue<Integer> q = new LinkedBlockingQueue<>();
        q.offer(1);
        q.offer(2);
        System.out.println(cow + " " + q.poll() + " " + q);
        LinkedList<Integer> ll = new LinkedList<>(List.of(1, 2, 3));
        System.out.println(ll.reversed() + " " + ll.peekLast());
    }
}
