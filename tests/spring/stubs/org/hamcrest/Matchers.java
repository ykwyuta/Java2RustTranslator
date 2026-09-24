package org.hamcrest;

/** テスト用のスタブ。 */
public final class Matchers {
    private Matchers() {}

    public static Matcher<String> containsString(String substring) {
        return null;
    }

    public static <T> Matcher<T> not(Matcher<T> matcher) {
        return null;
    }
}
