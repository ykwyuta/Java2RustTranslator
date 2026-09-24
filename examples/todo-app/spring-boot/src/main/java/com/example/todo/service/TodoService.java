package com.example.todo.service;

import com.example.todo.domain.ActivityAction;
import com.example.todo.domain.Priority;
import com.example.todo.domain.Todo;
import com.example.todo.domain.TodoFilter;
import com.example.todo.mapper.TodoMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.jspecify.annotations.Nullable;

@Service
@Transactional
public class TodoService {

    private final TodoMapper todoMapper;
    private final ActivityService activityService;
    private final Clock clock;

    public TodoService(TodoMapper todoMapper, ActivityService activityService, Clock clock) {
        this.todoMapper = todoMapper;
        this.activityService = activityService;
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

    public Todo create(String title, @Nullable String description, @Nullable LocalDate dueDate, Priority priority) {
        LocalDateTime now = now();
        Todo todo = new Todo();
        todo.setTitle(title);
        todo.setDescription(description);
        todo.setDone(false);
        todo.setDueDate(dueDate);
        todo.setPriority(priority);
        todo.setCreatedAt(now);
        todo.setUpdatedAt(now);
        todoMapper.insert(todo);
        activityService.record(ActivityAction.CREATE, todo);
        return todo;
    }

    public Todo update(long id, String title, @Nullable String description, @Nullable LocalDate dueDate, Priority priority,
                       boolean done) {
        Todo todo = findById(id);
        todo.setTitle(title);
        todo.setDescription(description);
        todo.setDueDate(dueDate);
        todo.setPriority(priority);
        todo.setDone(done);
        todo.setUpdatedAt(now());
        todoMapper.update(todo);
        activityService.record(ActivityAction.UPDATE, todo);
        return todo;
    }

    public Todo toggle(long id) {
        Todo todo = findById(id);
        todo.setDone(!todo.isDone());
        todo.setUpdatedAt(now());
        todoMapper.update(todo);
        activityService.record(todo.isDone() ? ActivityAction.COMPLETE : ActivityAction.REOPEN, todo);
        return todo;
    }

    public Todo delete(long id) {
        Todo todo = findById(id);
        activityService.record(ActivityAction.DELETE, todo);
        todoMapper.deleteById(id);
        return todo;
    }

    public int deleteCompleted() {
        List<Todo> completed = todoMapper.findAll(TodoFilter.COMPLETED, null);
        if (completed.isEmpty()) {
            return 0;
        }
        activityService.recordAll(ActivityAction.DELETE, completed);
        List<Long> ids = new ArrayList<>();
        for (Todo todo : completed) {
            ids.add(todo.getId());
        }
        return todoMapper.deleteByIds(ids);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
