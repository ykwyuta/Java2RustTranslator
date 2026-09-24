package calc;

import java.util.ArrayList;
import java.util.List;

/** テスト対象: 履歴を持つ電卓。 */
public class Calculator {
    private final List<Integer> history = new ArrayList<>();

    public int add(int a, int b) {
        return record(a + b);
    }

    public int divide(int a, int b) {
        return record(a / b);
    }

    public double average(int... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("no values");
        }
        double sum = 0;
        for (int v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    public List<Integer> history() {
        return history;
    }

    private int record(int v) {
        history.add(v);
        return v;
    }
}
