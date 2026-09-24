import java.util.ArrayList;
import java.util.List;

public class Init {
    static final List<String> LOG = new ArrayList<>();

    static class Base {
        static int baseCounter = log("Base.baseCounter", 10);

        static {
            log("Base static block", 0);
        }

        static void touch() {
            log("Base.touch", 0);
        }
    }

    static class Derived extends Base {
        static int first = log("Derived.first", 1);
        static int second = first + 1;
        static final int CONSTANT = 99;

        static {
            log("Derived static block second=" + second, 0);
        }

        Derived() {
            log("Derived()", 0);
        }
    }

    static class Broken {
        static int value = Integer.parseInt("not a number");

        static int get() {
            return value;
        }
    }

    static class Lazy {
        static {
            log("Lazy initialized", 0);
        }

        static int answer() {
            return 42;
        }
    }

    static int log(String s, int v) {
        LOG.add(s);
        return v;
    }

    public static void main(String[] args) {
        log("main start", 0);
        System.out.println(Derived.CONSTANT);
        log("after constant", 0);
        new Derived();
        new Derived();
        Base.touch();
        System.out.println(Derived.second + Base.baseCounter);
        for (int i = 0; i < 2; i++) {
            try {
                System.out.println(Broken.get());
            } catch (ExceptionInInitializerError e) {
                System.out.println("EIIE: " + e.getMessage() + " cause=" + e.getCause());
            } catch (NoClassDefFoundError e) {
                System.out.println("NCDFE: " + e.getMessage());
            }
        }
        log("before lazy", 0);
        System.out.println(Lazy.answer());
        for (String s : LOG) {
            System.out.println(s);
        }
    }
}
