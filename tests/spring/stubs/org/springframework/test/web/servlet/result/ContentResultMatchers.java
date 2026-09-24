package org.springframework.test.web.servlet.result;

import org.hamcrest.Matcher;
import org.springframework.test.web.servlet.ResultMatcher;

/** テスト用のスタブ。 */
public class ContentResultMatchers {
    public ResultMatcher string(String expected) {
        return null;
    }

    public ResultMatcher string(Matcher<? super String> matcher) {
        return null;
    }
}
