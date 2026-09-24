package org.springframework.test.web.servlet.request;

import org.springframework.test.web.servlet.RequestBuilder;

/** テスト用のスタブ。 */
public class MockHttpServletRequestBuilder implements RequestBuilder {
    public MockHttpServletRequestBuilder param(String name, String... values) {
        return this;
    }

    public MockHttpServletRequestBuilder accept(String mediaType) {
        return this;
    }
}
