package com.example;

/** A small program exercising the supported subset. */
public class Hello {
    static final int LIMIT = 10;
    static final String GREETING = "Hello";
    static int counter = 0;
    static int[] memo = new int[50];

    /** Computes fib with memoization. */
    static int fib(int n) {
        if (n < 2) return n;
        if (memo[n] != 0) return memo[n];
        int r = fib(n - 1) + fib(n - 2);
        memo[n] = r;
        return r;
    }

    static long fact(int n) {
        long r = 1;
        for (int i = 2; i <= n; i++) {
            r *= i;
        }
        return r;
    }

    static int max(int a, int b) { return a > b ? a : b; }
    static double max(double a, double b) { return a > b ? a : b; }

    public static void main(String[] args) {
        System.out.println(GREETING + ", world!");
        for (int i = 0; i < LIMIT; i++) {
            if (i % 3 == 0) continue;
            counter++;
            System.out.print(fib(i) + " ");
        }
        System.out.println();
        System.out.println("counter=" + counter);
        System.out.println("20! = " + fact(20) + ", 21! overflows to " + fact(21));
        int x = Integer.MAX_VALUE;
        x++;
        System.out.println("overflow: " + x + " " + (x >>> 28) + " " + (-7 / 2) + " " + (-7 % 2));
        char c = 'A';
        c += 2;
        System.out.println("char: " + c + " " + (int) c + " " + (char) (c + 1));
        double d = 10.0 / 3;
        System.out.println("double: " + d + " " + (int) d + " " + 1e10 + " " + (float) d + " " + max(1.5, 2.5) + " " + max(3, 4));
        StringBuilder sb = new StringBuilder();
        String s = "abcdef";
        for (int i = s.length() - 1; i >= 0; i--) {
            sb.append(s.charAt(i));
        }
        System.out.println(sb.toString() + " " + s.substring(1, 3).toUpperCase() + " " + s.indexOf("cd"));
        int[] arr = {5, 3, 9, 1, 7};
        java.util.Arrays.sort(arr);
        int sum = 0;
        for (int v : arr) sum += v;
        System.out.println(java.util.Arrays.toString(arr) + " sum=" + sum);
        int[][] grid = new int[3][4];
        grid[1][2] = 42;
        grid[2][3] += 5;
        System.out.println(grid[1][2] + grid[2][3] + " len=" + grid.length + "x" + grid[0].length);
        outer:
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                if (j == 3) continue outer;
                if (i == 3) break outer;
                System.out.print(i * 10 + j + ",");
            }
        }
        System.out.println();
        int k = 0;
        do {
            k += 2;
            if (k == 4) continue;
            System.out.print(k + ";");
        } while (k < 10);
        System.out.println();
        switch (k) {
            case 1, 2 -> System.out.println("small");
            case 10 -> System.out.println("ten");
            default -> System.out.println("other");
        }
        String cmd = "stop";
        switch (cmd) {
            case "go":
                System.out.println("going");
                break;
            case "stop":
            case "halt":
                System.out.println("stopping");
                break;
            default:
                System.out.println("?");
        }
        int y = 5;
        int z = y++ + ++y;
        System.out.println("y=" + y + " z=" + z);
        boolean flag = s.equals("abcdef") && !s.isEmpty();
        System.out.println("flag=" + flag + " " + Math.max(3, 7) + " " + Math.abs(-5) + " " + Math.sqrt(2.0));
    }
}
