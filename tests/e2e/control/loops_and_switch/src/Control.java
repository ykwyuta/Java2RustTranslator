public class Control {
    static int classify(int n) {
        switch (n % 4) {
            case 0:
                return 100;
            case 1:
            case -1:
                return 200;
            default:
                if (n > 50) {
                    break;
                }
                return 300;
        }
        return 400;
    }

    static String day(int d) {
        String name;
        switch (d) {
            case 1 -> name = "Mon";
            case 2 -> name = "Tue";
            case 6, 7 -> {
                name = "Weekend";
            }
            default -> name = "Other";
        }
        return name;
    }

    static int sumUntil(int limit) {
        int sum = 0;
        int i = 0;
        while (true) {
            i++;
            if (i % 2 == 0) continue;
            if (i > limit) break;
            sum += i;
        }
        return sum;
    }

    public static void main(String[] args) {
        for (int n = -3; n < 60; n += 7) {
            System.out.print(classify(n) + " ");
        }
        System.out.println();
        for (int d = 0; d <= 7; d++) {
            System.out.print(day(d) + ",");
        }
        System.out.println();
        System.out.println(sumUntil(20));

        search:
        for (int i = 2; i < 30; i++) {
            for (int j = 2; j * j <= i; j++) {
                if (i % j == 0) continue search;
            }
            System.out.print(i + " ");
        }
        System.out.println();

        int n = 27;
        int steps = 0;
        do {
            n = (n % 2 == 0) ? n / 2 : 3 * n + 1;
            steps++;
        } while (n != 1);
        System.out.println("collatz steps: " + steps);

        block:
        {
            for (int i = 0; i < 10; i++) {
                if (i == 4) break block;
                System.out.print(i);
            }
            System.out.print("unreachable");
        }
        System.out.println();

        int count = 0;
        for (int i = 0, j = 10; i < j; i++, j--) {
            count += j - i;
        }
        System.out.println("count=" + count);

        char grade = 'B';
        switch (grade) {
            case 'A' -> System.out.println("excellent");
            case 'B', 'C' -> System.out.println("good");
            default -> System.out.println("?");
        }
        int x = 0;
        while (x < 3) x++;
        System.out.println(x);
        for (;;) {
            x *= 3;
            if (x > 1000) break;
        }
        System.out.println(x);
    }
}
