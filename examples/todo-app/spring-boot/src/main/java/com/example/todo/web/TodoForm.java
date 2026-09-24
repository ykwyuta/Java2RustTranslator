package com.example.todo.web;

import com.example.todo.domain.Todo;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/** 登録・編集フォーム。name 属性は title / description / dueDate / done。 */
public class TodoForm {

    @NotBlank(message = "タイトルを入力してください")
    @Size(max = 100, message = "タイトルは100文字以内で入力してください")
    private String title;

    @Size(max = 1000, message = "説明は1000文字以内で入力してください")
    private String description;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;

    private boolean done;

    public static TodoForm from(Todo todo) {
        TodoForm form = new TodoForm();
        form.setTitle(todo.getTitle());
        form.setDescription(todo.getDescription());
        form.setDueDate(todo.getDueDate());
        form.setDone(todo.isDone());
        return form;
    }

    /** 前後の空白を除いたタイトル。 */
    public String normalizedTitle() {
        return title.strip();
    }

    /** 空の説明は null として保存する。 */
    public String normalizedDescription() {
        if (description == null || description.isBlank()) {
            return null;
        }
        return description.strip();
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }
}
