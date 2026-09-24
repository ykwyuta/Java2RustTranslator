package org.springframework.test.web.servlet.request;

/** テスト用のスタブ。 */
public final class MockMvcRequestBuilders {
    private MockMvcRequestBuilders() {}

    public static MockHttpServletRequestBuilder get(String uriTemplate, Object... uriVariables) {
        return new MockHttpServletRequestBuilder();
    }

    public static MockHttpServletRequestBuilder post(String uriTemplate, Object... uriVariables) {
        return new MockHttpServletRequestBuilder();
    }
}
