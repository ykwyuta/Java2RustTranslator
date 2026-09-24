package com.example.todo.service;

import com.example.todo.domain.Activity;
import com.example.todo.domain.ActivityAction;
import com.example.todo.domain.Todo;
import com.example.todo.mapper.ActivityMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Todo の操作履歴。TodoService から呼ばれ、呼び出し元のトランザクションに参加する（propagation = REQUIRED）。
 * 履歴は todos を外部キーで参照するので、まだコミットしていない Todo の履歴は同じトランザクションでしか書けない。
 */
@Service
@Transactional
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final Clock clock;

    public ActivityService(ActivityMapper activityMapper, Clock clock) {
        this.activityMapper = activityMapper;
        this.clock = clock;
    }

    public void record(ActivityAction action, Todo todo) {
        Activity activity = new Activity();
        activity.setTodoId(todo.getId());
        activity.setAction(action);
        activity.setTitle(todo.getTitle());
        activity.setCreatedAt(LocalDateTime.now(clock));
        activityMapper.insert(activity);
    }

    public void recordAll(ActivityAction action, List<Todo> todos) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Activity> activities = new ArrayList<>();
        for (Todo todo : todos) {
            Activity activity = new Activity();
            activity.setTodoId(todo.getId());
            activity.setAction(action);
            activity.setTitle(todo.getTitle());
            activity.setCreatedAt(now);
            activities.add(activity);
        }
        activityMapper.insertAll(activities);
    }

    @Transactional(readOnly = true)
    public List<Activity> recent(int limit) {
        return activityMapper.findRecent(limit);
    }
}
