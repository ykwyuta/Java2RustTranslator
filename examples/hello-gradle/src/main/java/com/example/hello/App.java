package com.example.hello;

/** FizzBuzz と素数の列挙。{@code ./gradlew translateToRust cargoRun} で Rust 版を実行できる。 */
public class App {
    static boolean isPrime(int n) {
        if (n < 2) return false;
        for (int i = 2; (long) i * i <= n; i++) {
            if (n % i == 0) return false;
        }
        return true;
    }

    static String fizzBuzz(int i) {
        if (i % 15 == 0) return "FizzBuzz";
        if (i % 3 == 0) return "Fizz";
        if (i % 5 == 0) return "Buzz";
        return Integer.toString(i);
    }

    public static void main(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 15; i++) {
            if (i > 1) sb.append(' ');
            sb.append(fizzBuzz(i));
        }
        System.out.println(sb);
        int count = 0;
        for (int n = 0; n < 100; n++) {
            if (isPrime(n)) count++;
        }
        System.out.println("primes below 100: " + count);
    }
}
