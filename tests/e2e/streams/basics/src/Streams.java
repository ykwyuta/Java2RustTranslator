import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class Streams {
    record Person(String name, String city, int age) {}

    public static void main(String[] args) {
        List<String> words = List.of("apple", "banana", "cherry", "date", "fig", "grape", "kiwi", "banana");
        List<String> upper = words.stream().filter(w -> w.length() > 4).map(String::toUpperCase).collect(Collectors.toList());
        System.out.println(upper);
        System.out.println(words.stream().distinct().sorted(Comparator.comparing(String::length).thenComparing(Comparator.reverseOrder())).toList());
        System.out.println(words.stream().mapToInt(String::length).sum() + " " + words.stream().count());
        System.out.println(words.stream().collect(Collectors.joining(", ", "[", "]")));
        System.out.println(words.stream().collect(Collectors.groupingBy(String::length)));
        Map<Character, Long> byInitial = words.stream().collect(Collectors.groupingBy(w -> w.charAt(0), TreeMap::new, Collectors.counting()));
        System.out.println(byInitial);
        System.out.println(words.stream().collect(Collectors.partitioningBy(w -> w.contains("a"))));
        Map<String, Integer> lengths = words.stream().distinct().collect(Collectors.toMap(Function.identity(), String::length));
        System.out.println(lengths);
        System.out.println(words.stream().collect(Collectors.toMap(w -> w.charAt(0), w -> 1, Integer::sum)));
        try {
            words.stream().collect(Collectors.toMap(w -> w, w -> 1));
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
        }

        // 遅延評価: 要素ごとに段を通り、limit で打ち切る
        List<Integer> seen = new ArrayList<>();
        List<Integer> firstTwo = Stream.iterate(1, x -> x * 3)
                .peek(seen::add)
                .filter(x -> x % 2 == 1)
                .map(x -> {
                    System.out.println("map " + x);
                    return x + 1;
                })
                .limit(2)
                .collect(Collectors.toList());
        System.out.println(firstTwo + " seen=" + seen);
        System.out.println(Stream.of("x", "y", "z").anyMatch(s -> {
            System.out.println("test " + s);
            return s.equals("y");
        }));

        System.out.println(IntStream.rangeClosed(1, 5).map(x -> x * x).boxed().collect(Collectors.toList()));
        IntSummaryStatistics stats = IntStream.of(4, 8, 15, 16, 23, 42).summaryStatistics();
        System.out.println(stats);
        System.out.println(stats.getMin() + " " + stats.getMax() + " " + stats.getAverage() + " " + stats.getSum());
        OptionalDouble avg = IntStream.range(0, 0).average();
        System.out.println(avg + " " + avg.isPresent() + " " + IntStream.range(1, 4).average().getAsDouble());
        OptionalInt max = Arrays.stream(new int[]{3, 9, 2}).max();
        System.out.println(max + " " + max.getAsInt());
        System.out.println(Arrays.stream(new double[]{0.1, 0.2, 0.3}).sum());
        System.out.println(IntStream.iterate(1, i -> i <= 100, i -> i * 2).mapToObj(Integer::toString).collect(Collectors.joining("-")));
        System.out.println(Stream.of(List.of(1, 2), List.of(3), List.<Integer>of()).flatMap(List::stream).map(i -> i * 10).toList());
        System.out.println(IntStream.range(0, 10).skip(3).limit(4).reduce(0, Integer::sum));
        System.out.println(Stream.of(5, 3, 8).reduce(Integer::max).get());
        System.out.println(Stream.of(1, 2, 3, 4, 5, 1).takeWhile(x -> x < 4).toList() + " " + Stream.of(1, 2, 3, 4, 1).dropWhile(x -> x < 3).toList());
        System.out.println("hello".chars().filter(c -> c == 'l').count());

        List<Person> people = List.of(new Person("Ann", "Tokyo", 31), new Person("Bob", "Osaka", 25),
                new Person("Cid", "Tokyo", 45), new Person("Dee", "Kyoto", 25));
        System.out.println(people.stream().collect(Collectors.groupingBy(Person::city, Collectors.mapping(Person::name, Collectors.toList()))));
        Map<Integer, Double> byAge = people.stream().collect(Collectors.groupingBy(Person::age, TreeMap::new, Collectors.averagingInt(Person::age)));
        System.out.println(byAge);
        System.out.println(people.stream().collect(Collectors.summingInt(Person::age)) + " "
                + people.stream().collect(Collectors.averagingDouble(p -> p.age() / 2.0)));
        Optional<Person> oldest = people.stream().max(Comparator.comparingInt(Person::age));
        System.out.println(oldest.map(Person::name).orElse("none"));
        System.out.println(people.stream().filter(p -> p.age() > 100).findFirst().map(Person::name).orElse("nobody"));
        System.out.println(people.stream().sorted(Comparator.comparing(Person::age).thenComparing(Person::name, Comparator.reverseOrder()))
                .map(Person::name).collect(Collectors.joining(",")));
        Set<String> cities = people.stream().map(Person::city).collect(Collectors.toSet());
        System.out.println(cities);
        int count = people.stream().collect(Collectors.collectingAndThen(Collectors.toList(), List::size));
        System.out.println(count);

        Optional<String> empty = Optional.empty();
        System.out.println(empty + " " + Optional.of("v") + " " + empty.isEmpty() + " " + Optional.ofNullable(null).orElseGet(() -> "gen"));
        try {
            empty.get();
        } catch (java.util.NoSuchElementException e) {
            System.out.println(e.getMessage());
        }
        try {
            Stream.of(1, 0, 2).map(x -> 10 / x).forEach(System.out::println);
        } catch (ArithmeticException e) {
            System.out.println("caught " + e.getMessage());
        }
        Stream<String> once = Stream.of("a");
        once.count();
        try {
            once.count();
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
        }
        String[] arr = Stream.of("b", "a").sorted().toArray(String[]::new);
        System.out.println(arr.length + arr[0]);
    }
}
