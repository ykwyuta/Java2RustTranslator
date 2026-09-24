package io.github.ykwyuta.j2r.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class OgnlTest {

    @Test
    void parsesNullChecksJoinedByAnd() throws Exception {
        assertThat(Ognl.parse("keyword != null and keyword != ''")).isEqualTo(new Ognl.And(
                new Ognl.Compare("!=", new Ognl.Path(List.of("keyword")), new Ognl.NullLit()),
                new Ognl.Compare("!=", new Ognl.Path(List.of("keyword")), new Ognl.StrLit(""))));
    }

    @Test
    void parsesMethodCallsPropertiesAndWordOperators() throws Exception {
        assertThat(Ognl.parse("filter.name() == 'ACTIVE' or not (book.stock gte 0)")).isEqualTo(new Ognl.Or(
                new Ognl.Compare("==", new Ognl.MethodCall(new Ognl.Path(List.of("filter")), "name"), new Ognl.StrLit("ACTIVE")),
                new Ognl.Not(new Ognl.Compare(">=", new Ognl.Path(List.of("book", "stock")), new Ognl.NumLit("0")))));
    }

    @Test
    void rejectsMethodCallsWithArguments() {
        assertThatThrownBy(() -> Ognl.parse("name.startsWith('a')")).isInstanceOf(Ognl.ParseException.class);
    }
}
