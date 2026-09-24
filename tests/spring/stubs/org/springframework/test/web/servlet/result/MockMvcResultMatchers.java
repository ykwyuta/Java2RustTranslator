package org.springframework.test.web.servlet.result;

import org.springframework.test.web.servlet.ResultMatcher;

/** テスト用のスタブ。 */
public final class MockMvcResultMatchers {
    private MockMvcResultMatchers() {}

    public static StatusResultMatchers status() {
        return new StatusResultMatchers();
    }

    public static ContentResultMatchers content() {
        return new ContentResultMatchers();
    }

    public static ViewResultMatchers view() {
        return new ViewResultMatchers();
    }

    public static FlashAttributeResultMatchers flash() {
        return new FlashAttributeResultMatchers();
    }

    public static ResultMatcher redirectedUrl(String url) {
        return null;
    }
}
