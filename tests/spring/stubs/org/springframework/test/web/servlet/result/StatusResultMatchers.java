package org.springframework.test.web.servlet.result;

import org.springframework.test.web.servlet.ResultMatcher;

/** テスト用のスタブ。 */
public class StatusResultMatchers {
    public ResultMatcher isOk() {
        return null;
    }

    public ResultMatcher is3xxRedirection() {
        return null;
    }

    public ResultMatcher isNotFound() {
        return null;
    }
}
