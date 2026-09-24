package io.github.ykwyuta.j2r.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NamingTest {
    @Test
    void snakeCase() {
        assertThat(Naming.toSnakeCase("helloWorld")).isEqualTo("hello_world");
        assertThat(Naming.toSnakeCase("HelloWorld")).isEqualTo("hello_world");
        assertThat(Naming.toSnakeCase("parseHTTPResponse")).isEqualTo("parse_http_response");
        assertThat(Naming.toSnakeCase("value2Max")).isEqualTo("value2_max");
        assertThat(Naming.toSnakeCase("N")).isEqualTo("n");
        assertThat(Naming.toSnakeCase("already_snake")).isEqualTo("already_snake");
    }

    @Test
    void screamingSnake() {
        assertThat(Naming.toScreamingSnakeCase("MAX_SIZE")).isEqualTo("MAX_SIZE");
        assertThat(Naming.toScreamingSnakeCase("counter")).isEqualTo("COUNTER");
        assertThat(Naming.toScreamingSnakeCase("totalCount")).isEqualTo("TOTAL_COUNT");
    }

    @Test
    void keywordsAreEscaped() {
        assertThat(Naming.valueName("match")).isEqualTo("r#match");
        assertThat(Naming.valueName("type")).isEqualTo("r#type");
        assertThat(Naming.valueName("self")).isEqualTo("self_");
        assertThat(Naming.valueName("count")).isEqualTo("count");
    }

    @Test
    void crateName() {
        assertThat(Naming.crateName("My-App")).isEqualTo("my_app");
        assertThat(Naming.crateName("1app")).isEqualTo("j_1app");
    }
}
