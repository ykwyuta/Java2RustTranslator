import java.util.*;

public class Report {
    public static void main(String[] args) {
        String csv = "name,qty,price\nwidget,4,2.50\ngadget,10,0.99\ngizmo,1,15.00";
        String[] lines = csv.split("\n");
        System.out.printf("%-8s|%5s|%8s%n", "item", "qty", "total");
        double grand = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            int qty = Integer.parseInt(cols[1]);
            double price = Double.parseDouble(cols[2]);
            double total = qty * price;
            grand += total;
            System.out.printf("%-8s|%5d|%8.2f%n", cols[0], qty, total);
        }
        System.out.println(String.format("grand total: %,.2f (%d items)", grand, lines.length - 1));
        System.out.println(String.format("%05d %x %X %o %e %+.1f %10.3e", 42, 255, 3054, 8, 12345.678, 3.14159, 0.000123));
        System.out.println(String.format("%s %S %b %c %%", "hi", "hi", true, 'z'));
        System.out.println(String.join("-", "a", "b", "c") + " " + String.join("/", List.of("x", "y")));
        System.out.println(Arrays.toString("  lots   of   space ".trim().split("\\s+")) + " " + Arrays.toString("a1b22c333".split("[0-9]+")));
        StringBuilder sb = new StringBuilder();
        for (String w : "the quick brown fox".split(" ")) {
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        sb.setLength(sb.length() - 1);
        sb.insert(0, '"').append('"');
        System.out.println(sb + " len=" + sb.length());
        String text = "Mississippi";
        Map<Character, Integer> freq = new TreeMap<>();
        for (char c : text.toCharArray()) {
            freq.merge(c, 1, Integer::sum);
        }
        System.out.println(freq);
        System.out.println("%d-%s".formatted(7, "seven") + " " + "abc".compareToIgnoreCase("ABD") + " " + String.valueOf((Object) null));
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 3; i++) parts.add("p" + i);
        System.out.println(String.join(", ", parts) + " | " + parts);
        double[] vals = {1.0 / 3, 2.0 / 3, 100.0, 1e-5, 123456789.0};
        for (double v : vals) System.out.print(String.format("%.3f ", v));
        System.out.println();
        System.out.println(String.format("[%8.3f] [%-8.2f] [%08.2f]", Math.E, Math.PI, -2.5));
        long big = 9_000_000_000L;
        System.out.println(String.format("%,d %d", big, Long.MIN_VALUE) + " " + Integer.toHexString(255) + " " + Long.toBinaryString(5L));
    }
}
