package com.example.todo.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

/** todos テーブルの 1 行。MyBatis が列名（snake_case）をプロパティ（camelCase）に対応付ける。 */
public class Todo {

    private Long id;
    private String title;
    private @Nullable String description;
    private boolean done;
    private @Nullable LocalDate dueDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 未完了で、期限が今日より前なら期限切れ。 */
    public boolean isOverdue(LocalDate today) {
        return !done && dueDate != null && dueDate.isBefore(today);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public @Nullable LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(@Nullable LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
