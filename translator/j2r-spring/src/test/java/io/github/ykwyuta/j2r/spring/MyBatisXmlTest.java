package io.github.ykwyuta.j2r.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MyBatisXmlTest {

    @Test
    void parsesStatementsAndExpandsIncludes(@TempDir Path dir) throws Exception {
        Path xml = dir.resolve("TodoMapper.xml");
        Files.writeString(xml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.example.TodoMapper">
                    <sql id="columns">id, title</sql>
                    <select id="find">SELECT <include refid="columns"/> FROM todos
                        <where><if test="done != null">AND done = #{done, jdbcType=BOOLEAN}</if></where>
                    </select>
                    <insert id="insert" useGeneratedKeys="true" keyProperty="id">INSERT INTO todos (title) VALUES (#{title})</insert>
                    <delete id="purge">DELETE FROM todos WHERE title = '${title}'</delete>
                </mapper>
                """);
        var mappers = MyBatisXml.scan(List.of(dir));
        var mapper = mappers.get("com.example.TodoMapper");
        assertThat(mapper).isNotNull();

        var find = mapper.statements().get("find");
        assertThat(find.kind()).isEqualTo(MyBatisXml.Kind.SELECT);
        assertThat(find.isDynamic()).isTrue();
        assertThat(((MyBatisXml.Text) find.body().get(0)).sql().strip()).isEqualTo("SELECT id, title FROM todos");
        assertThat(find.body().get(1)).isEqualTo(new MyBatisXml.Clause(true, List.of(
                new MyBatisXml.If("done != null", List.of(new MyBatisXml.Text("AND done = "), new MyBatisXml.Bind("done"))))));

        var insert = mapper.statements().get("insert");
        assertThat(insert.keyProperty()).isEqualTo("id");
        assertThat(insert.isDynamic()).isFalse();

        assertThat(mapper.statements().get("purge").body()).anyMatch(n -> n instanceof MyBatisXml.Unsupported);
    }

    @Test
    void longSqlUsesLineContinuations() {
        assertThat(MapperGenerator.sqlLiteral("SELECT id\n  FROM todos\n WHERE id = $1")).isEqualTo("\"SELECT id FROM todos WHERE id = $1\"");
        String longSql = "SELECT id, title, description, done, due_date, created_at, updated_at\nFROM todos\nWHERE id = $1";
        assertThat(MapperGenerator.sqlLiteral(longSql)).isEqualTo(
                "\"SELECT id, title, description, done, due_date, created_at, updated_at \\\nFROM todos \\\nWHERE id = $1\"");
    }
}
