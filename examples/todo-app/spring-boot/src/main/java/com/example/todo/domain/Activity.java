package com.example.todo.domain;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/** activities テーブルの 1 行（Todo の操作履歴）。Todo を削除しても履歴は残る（todo_id は null になる）。 */
public class Activity {

    private @Nullable Long id;
    private @Nullable Long todoId;
    private ActivityAction action;
    private String title;
    private LocalDateTime createdAt;

    public @Nullable Long getId() {
        return id;
    }

    public void setId(@Nullable Long id) {
        this.id = id;
    }

    public @Nullable Long getTodoId() {
        return todoId;
    }

    public void setTodoId(@Nullable Long todoId) {
        this.todoId = todoId;
    }

    public ActivityAction getAction() {
        return action;
    }

    public void setAction(ActivityAction action) {
        this.action = action;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
