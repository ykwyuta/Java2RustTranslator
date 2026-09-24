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
        assertThat(find.body().get(1)).isEqualTo(MyBatisXml.Trim.where(List.of(
                new MyBatisXml.If("done != null", List.of(new MyBatisXml.Text("AND done = "), new MyBatisXml.Bind("done"))))));

        var insert = mapper.statements().get("insert");
        assertThat(insert.keyProperty()).isEqualTo("id");
        assertThat(insert.isDynamic()).isFalse();

        var purge = mapper.statements().get("purge");
        assertThat(purge.body()).contains(new MyBatisXml.Subst("title"));
        assertThat(purge.isDynamic()).isTrue();
    }

    @Test
    void parsesForeachTrimBindAndResultMaps(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("ItemMapper.xml"), """
                <mapper namespace="com.example.ItemMapper">
                    <resultMap id="itemMap" type="Item" autoMapping="false">
                        <id property="id" column="item_id"/>
                        <result property="name" column="item_name"/>
                        <association property="owner" javaType="Owner"/>
                    </resultMap>
                    <select id="find" resultMap="itemMap">
                        <bind name="pattern" value="'%' + keyword + '%'"/>
                        SELECT * FROM items
                        <trim prefix="WHERE" prefixOverrides="AND |OR ">
                            <foreach collection="ids" item="id" index="i" open="id IN (" separator="," close=")">#{id}</foreach>
                        </trim>
                    </select>
                </mapper>
                """);
        var mapper = MyBatisXml.scan(List.of(dir)).get("com.example.ItemMapper");
        var rm = mapper.resultMaps().get("itemMap");
        assertThat(rm.mappings()).containsExactly(new MyBatisXml.ResultMapping("id", "item_id"),
                new MyBatisXml.ResultMapping("name", "item_name"));
        assertThat(rm.autoMapping()).isFalse();
        assertThat(rm.unsupported()).containsExactly("<association> in <resultMap>");

        var find = mapper.statements().get("find");
        assertThat(find.resultMap()).isEqualTo("itemMap");
        assertThat(find.body().get(0)).isEqualTo(new MyBatisXml.BindVar("pattern", "'%' + keyword + '%'"));
        var trim = (MyBatisXml.Trim) find.body().get(2);
        assertThat(trim.prefix()).isEqualTo("WHERE");
        assertThat(trim.prefixOverrides()).containsExactly("AND", "OR");
        assertThat(trim.body()).containsExactly(new MyBatisXml.Foreach("ids", "id", "i", "id IN (", ")", ",",
                List.of(new MyBatisXml.Bind("id"))));
    }

    @Test
    void annotationScriptsAreDynamicSql() {
        var st = MyBatisXml.annotated("find", MyBatisXml.Kind.SELECT,
                "<script>SELECT * FROM items <where><if test='x != null'>x = #{x}</if></where></script>", null, null, "row");
        assertThat(st.isDynamic()).isTrue();
        assertThat(st.resultMap()).isEqualTo("row");
    }

    @Test
    void testMethodNamesBecomeSnakeCase() {
        assertThat(TestGenerator.snake("is3xxRedirection")).isEqualTo("is_3xx_redirection");
        assertThat(TestGenerator.snake("containsString")).isEqualTo("contains_string");
        assertThat(TestGenerator.snake("isOk")).isEqualTo("is_ok");
    }

    @Test
    void longSqlUsesLineContinuations() {
        assertThat(MapperGenerator.sqlLiteral("SELECT id\n  FROM todos\n WHERE id = $1")).isEqualTo("\"SELECT id FROM todos WHERE id = $1\"");
        String longSql = "SELECT id, title, description, done, due_date, created_at, updated_at\nFROM todos\nWHERE id = $1";
        assertThat(MapperGenerator.sqlLiteral(longSql)).isEqualTo(
                "\"SELECT id, title, description, done, due_date, created_at, updated_at \\\nFROM todos \\\nWHERE id = $1\"");
    }
}
