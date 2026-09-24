package com.example.todo.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.example.todo.domain.Priority;
import com.example.todo.domain.Todo;
import com.example.todo.domain.TodoFilter;
import com.example.todo.service.TodoService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** PostgreSQL（application.yml の接続先）に対して動かす結合テスト。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TodoControllerTest.FixedClockConfig.class)
class TodoControllerTest {

    /** テスト用の固定時計（2026-09-24 12:00）。 */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId zone = ZoneId.systemDefault();
            return Clock.fixed(LocalDateTime.of(2026, 9, 24, 12, 0).atZone(zone).toInstant(), zone);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TodoService todoService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM activities");
        jdbcTemplate.update("DELETE FROM todos");
    }

    /** 一覧の Todo のタイトルの表示（最近の操作の一覧と区別する）。 */
    private static String title(String title) {
        return "<span class=\"title\">" + title + "</span>";
    }

    @Test
    void createAndList() throws Exception {
        mockMvc.perform(post("/todos").param("title", "  牛乳を買う ").param("description", "").param("dueDate", "2026-10-01"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/todos"))
                .andExpect(flash().attribute("message", "「牛乳を買う」を追加しました"));

        mockMvc.perform(get("/todos"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("牛乳を買う")))
                .andExpect(content().string(containsString("2026/10/01")));
    }

    @Test
    void validationErrorsRerenderTheForm() throws Exception {
        mockMvc.perform(post("/todos").param("title", " ").param("dueDate", "not-a-date"))
                .andExpect(status().isOk())
                .andExpect(view().name("todos/form"))
                .andExpect(content().string(containsString("タイトルを入力してください")))
                .andExpect(content().string(containsString("期限は yyyy-MM-dd 形式で入力してください")));
    }

    @Test
    void toggleAndFilter() throws Exception {
        Todo done = todoService.create("完了するもの", null, null, Priority.MEDIUM);
        todoService.create("残すもの", null, null, Priority.MEDIUM);

        mockMvc.perform(post("/todos/{id}/toggle", done.getId()).param("filter", "active"))
                .andExpect(redirectedUrl("/todos?filter=active"));

        mockMvc.perform(get("/todos").param("filter", "active"))
                .andExpect(content().string(containsString(title("残すもの"))))
                .andExpect(content().string(not(containsString(title("完了するもの")))));
        mockMvc.perform(get("/todos").param("filter", "completed"))
                .andExpect(content().string(containsString(title("完了するもの"))))
                .andExpect(content().string(not(containsString(title("残すもの")))));
    }

    @Test
    void searchByKeyword() throws Exception {
        todoService.create("Rust を勉強する", null, null, Priority.MEDIUM);
        todoService.create("掃除", null, null, Priority.MEDIUM);

        mockMvc.perform(get("/todos").param("q", "rust"))
                .andExpect(content().string(containsString(title("Rust を勉強する"))))
                .andExpect(content().string(not(containsString(title("掃除")))));
    }

    @Test
    void overdueTodoIsMarked() throws Exception {
        todoService.create("期限切れのもの", null, LocalDate.of(2026, 9, 23), Priority.MEDIUM);

        mockMvc.perform(get("/todos"))
                .andExpect(content().string(containsString("class=\"todo overdue\"")))
                .andExpect(content().string(containsString("期限切れ</span>")));
    }

    @Test
    void updateAndDelete() throws Exception {
        Todo todo = todoService.create("旧タイトル", null, null, Priority.MEDIUM);

        mockMvc.perform(post("/todos/{id}", todo.getId()).param("title", "新タイトル").param("done", "true"))
                .andExpect(redirectedUrl("/todos"))
                .andExpect(flash().attribute("message", "「新タイトル」を更新しました"));
        Todo updated = todoService.findById(todo.getId());
        assertEquals("新タイトル", updated.getTitle());
        assertTrue(updated.isDone());

        mockMvc.perform(post("/todos/completed/delete"))
                .andExpect(flash().attribute("message", "完了済みの 1 件を削除しました"));
        assertTrue(todoService.findAll(TodoFilter.ALL, null).isEmpty());
        mockMvc.perform(get("/todos"))
                .andExpect(content().string(containsString("削除: 新タイトル")))
                .andExpect(content().string(containsString("更新: 新タイトル")));
    }

    @Test
    void priorityAndActivities() throws Exception {
        mockMvc.perform(get("/todos/new"))
                .andExpect(status().isOk())
                .andExpect(view().name("todos/form"))
                .andExpect(content().string(containsString("<option value=\"MEDIUM\" selected=\"selected\">中</option>")));

        mockMvc.perform(post("/todos").param("title", "急ぎの用事").param("priority", "HIGH"))
                .andExpect(redirectedUrl("/todos"));
        mockMvc.perform(post("/todos").param("title", "優先度なし").param("priority", "URGENT"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("優先度が正しくありません")));

        mockMvc.perform(get("/todos"))
                .andExpect(content().string(containsString("class=\"priority priority-high\"")))
                .andExpect(content().string(containsString("追加: 急ぎの用事")))
                .andExpect(content().string(containsString("全 1 件（うち完了 0 件）")));
    }

    @Test
    void unknownTodoIs404() throws Exception {
        mockMvc.perform(get("/todos/{id}/edit", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("指定された Todo は見つかりませんでした")));
        mockMvc.perform(post("/todos/{id}/delete", 999_999)).andExpect(status().isNotFound());
        mockMvc.perform(get("/no-such-page").accept("text/html")).andExpect(status().isNotFound());
    }
}
