package com.example.todo.service;

/** 一覧画面に出す件数。 */
public record TodoSummary(long active, long completed) {

    public long total() {
        return active + completed;
    }
}
