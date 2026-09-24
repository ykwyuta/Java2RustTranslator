import java.util.ArrayList;
import java.util.List;

public class Bank {
    static class InsufficientFundsException extends Exception {
        private final int shortfall;

        InsufficientFundsException(String message, int shortfall) {
            super(message);
            this.shortfall = shortfall;
        }

        int getShortfall() {
            return shortfall;
        }
    }

    static class AccountLockedException extends RuntimeException {
        AccountLockedException(String id) {
            super("account " + id + " is locked");
        }
    }

    static class Resource implements AutoCloseable {
        private final String name;
        static final List<String> log = new ArrayList<>();

        Resource(String name) {
            this.name = name;
            log.add("open " + name);
        }

        void use(boolean fail) {
            log.add("use " + name);
            if (fail) throw new IllegalStateException("failed using " + name);
        }

        @Override
        public void close() {
            log.add("close " + name);
        }
    }

    static class Faulty implements AutoCloseable {
        private final String name;

        Faulty(String name) {
            this.name = name;
        }

        @Override
        public void close() {
            throw new IllegalStateException("close failed: " + name);
        }
    }

    static String withFaulty(boolean failBody) {
        try (Faulty a = new Faulty("a"); Faulty b = new Faulty("b")) {
            if (failBody) {
                throw new RuntimeException("body failed");
            }
            return "no exception";
        } catch (RuntimeException e) {
            StringBuilder sb = new StringBuilder(e.getMessage());
            for (Throwable s : e.getSuppressed()) {
                sb.append(" | suppressed: ").append(s.getMessage());
            }
            return sb.toString();
        }
    }

    private int balance;
    private final String id;
    private boolean locked;

    Bank(String id, int balance) {
        this.id = id;
        this.balance = balance;
    }

    void withdraw(int amount) throws InsufficientFundsException {
        if (locked) throw new AccountLockedException(id);
        if (amount > balance) {
            throw new InsufficientFundsException("need " + amount + " but have " + balance, amount - balance);
        }
        balance -= amount;
    }

    static int parseOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        } finally {
            System.out.println("parsed " + s);
        }
    }

    static int findFirstNegative(int[][] grid) {
        int found = -1;
        outer:
        for (int i = 0; i < grid.length; i++) {
            for (int j = 0; j < grid[i].length; j++) {
                try {
                    if (grid[i][j] < 0) {
                        found = i * 10 + j;
                        break outer;
                    }
                    if (grid[i][j] == 0) continue outer;
                } finally {
                    if (grid[i][j] == 0) System.out.println("zero at " + i + "," + j);
                }
            }
        }
        return found;
    }

    static String classify(Object o) {
        try {
            String s = (String) o;
            return "string of length " + s.length();
        } catch (ClassCastException e) {
            return "not a string";
        } catch (NullPointerException e) {
            return "null!";
        }
    }

    public static void main(String[] args) {
        Bank b = new Bank("A-1", 100);
        try {
            b.withdraw(30);
            b.withdraw(100);
            System.out.println("unreachable");
        } catch (InsufficientFundsException e) {
            System.out.println("error: " + e.getMessage() + " shortfall=" + e.getShortfall());
        }
        b.locked = true;
        try {
            b.withdraw(1);
        } catch (InsufficientFundsException | AccountLockedException e) {
            System.out.println("multi: " + e.getMessage() + " " + (e instanceof RuntimeException));
        }
        System.out.println(parseOr("42", 0) + " " + parseOr("x", -1));
        System.out.println(findFirstNegative(new int[][]{{1, 0, 5}, {2, 3, -4}, {7}}));
        System.out.println(classify("abc") + "; " + classify(12) + "; " + classify(null));
        try (Resource r1 = new Resource("db"); Resource r2 = new Resource("file")) {
            r1.use(false);
            r2.use(true);
        } catch (IllegalStateException e) {
            Resource.log.add("caught " + e.getMessage());
        }
        System.out.println(Resource.log);
        System.out.println(withFaulty(true));
        System.out.println(withFaulty(false));
        int[] data = {1, 2, 3};
        try {
            System.out.println(data[3]);
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("AIOOBE: " + e.getMessage());
        }
        try {
            Object o = "x";
            Integer i = (Integer) o;
        } catch (RuntimeException e) {
            System.out.println("CCE caught: " + (e instanceof ClassCastException));
        }
        try {
            throw new UnsupportedOperationException();
        } catch (Exception e) {
            System.out.println(e);
        }
        try {
            try {
                throw new IllegalArgumentException("inner");
            } finally {
                System.out.println("inner finally");
            }
        } catch (IllegalArgumentException e) {
            RuntimeException wrapped = new RuntimeException("wrapped", e);
            System.out.println(wrapped.getMessage() + " cause=" + wrapped.getCause().getMessage());
        }
        System.out.println(total(new int[]{5, 5, 5}));
        throw new AccountLockedException("B-2");
    }

    static int total(int[] xs) {
        int sum = 0;
        for (int x : xs) {
            try {
                sum += x;
                if (sum > 7) return sum * 100;
            } finally {
                sum++;
            }
        }
        return sum;
    }
}
