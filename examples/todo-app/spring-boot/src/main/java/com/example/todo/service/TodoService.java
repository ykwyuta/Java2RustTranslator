package com.example.todo.service;

import com.example.todo.domain.Todo;
import com.example.todo.domain.TodoFilter;
import com.example.todo.mapper.TodoMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.Nullable;

@Service
@Transactional
public class TodoService {

    private final TodoMapper todoMapper;
    private final Clock clock;

    public TodoService(TodoMapper todoMapper, Clock clock) {
        this.todoMapper = todoMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Todo> findAll(TodoFilter filter, @Nullable String keyword) {
        return todoMapper.findAll(filter, keyword == null ? null : keyword.strip());
    }

    @Transactional(readOnly = true)
    public Todo findById(long id) {
        return todoMapper.findById(id).orElseThrow(() -> new TodoNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public TodoSummary summary() {
        return new TodoSummary(todoMapper.countByDone(false), todoMapper.countByDone(true));
    }

    public Todo create(String title, @Nullable String description, @Nullable LocalDate dueDate) {
        LocalDateTime now = now();
        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setDescription(description);
        todo.setDone(false);
        todo.setDueDate(dueDate);
        todo.setCreatedAt(now);
        todo.setUpdatedAt(now);
        todoMapper.insert(todo);
        return todo;
    }

    public Todo update(long id, String title, @Nullable String description, @Nullable LocalDate dueDate, boolean done) {
        Todo todo = findById(id);
        todo.setTitle(title);
        todo.setDescription(description);
        todo.setDueDate(dueDate);
        todo.setDone(done);
        todo.setUpdatedAt(now());
        todoMapper.update(todo);
        return todo;
    }

    public Todo toggle(long id) {
        Todo todo = findById(id);
        todo.setDone(!todo.isDone());
        todo.setUpdatedAt(now());
        todoMapper.update(todo);
        return todo;
    }

    public Todo delete(long id) {
        Todo todo = findById(id);
        todoMapper.deleteById(id);
        return todo;
    }

    public int deleteCompleted() {
        return todoMapper.deleteCompleted();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
