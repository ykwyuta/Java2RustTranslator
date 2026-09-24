package org.springframework.test.web.servlet;

/** テスト用のスタブ。 */
public interface ResultActions {
    ResultActions andExpect(ResultMatcher matcher) throws Exception;
}
