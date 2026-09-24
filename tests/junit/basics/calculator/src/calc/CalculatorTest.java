package calc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class CalculatorTest {
    private Calculator calc;

    @BeforeEach
    void setUp() {
        calc = new Calculator();
    }

    @AfterEach
    void tearDown() {
        assertNotNull(calc.history());
    }

    @Test
    void addsNumbers() {
        assertEquals(5, calc.add(2, 3));
        assertEquals(-1, calc.add(2, -3), "negative sum");
        assertIterableEquals(List.of(5, -1), calc.history());
    }

    @Test
    void averageWithDelta() {
        assertEquals(2.333, calc.average(1, 2, 4), 0.001);
        assertTrue(calc.history().isEmpty());
        assertFalse(calc.average(1) > 1);
    }

    @Test
    void divisionByZeroThrows() {
        ArithmeticException e = assertThrows(ArithmeticException.class, () -> calc.divide(1, 0));
        assertEquals("/ by zero", e.getMessage());
        IllegalArgumentException iae = assertThrows(IllegalArgumentException.class, () -> calc.average());
        assertEquals("no values", iae.getMessage());
        RuntimeException re = assertThrows(RuntimeException.class, () -> calc.average());
        assertInstanceOf(IllegalArgumentException.class, re);
    }

    @Test
    void groupedAssertions() {
        assertAll("sums",
                () -> assertEquals(4, calc.add(2, 2)),
                () -> assertEquals(0, calc.add(0, 0)));
        int[] expected = {4, 0};
        int[] actual = calc.history().stream().mapToInt(Integer::intValue).toArray();
        assertArrayEquals(expected, actual);
        assertSame(calc.history(), calc.history());
        assertNull(null);
        double avg = assertDoesNotThrow(() -> calc.average(3, 5));
        assertEquals(4.0, avg);
    }

    @Test
    void wrongExpectation() {
        assertEquals(10, calc.add(2, 3), "deliberately wrong");
    }

    @Test
    void wrongException() {
        assertThrows(IllegalStateException.class, () -> calc.divide(1, 0));
    }

    @Test
    void groupedFailures() {
        assertAll(
                () -> assertEquals(1, calc.add(1, 1)),
                () -> assertTrue(calc.history().size() > 5, () -> "history too short"));
    }

    @Test
    void explicitFail() {
        if (calc.history().isEmpty()) {
            fail("history should not be empty");
        }
    }

    @Test
    void uncaughtException() {
        calc.divide(10, 0);
    }

    @Test
    void assumptionAborts() {
        assumeTrue(calc.history().size() > 100, "needs history");
        fail("not reached");
    }

    @Test
    @Disabled("not implemented yet")
    void disabledTest() {
        fail("disabled");
    }
}
