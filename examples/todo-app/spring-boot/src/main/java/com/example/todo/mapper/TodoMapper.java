package com.example.todo.mapper;

import com.example.todo.domain.Todo;
import com.example.todo.domain.TodoFilter;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jspecify.annotations.Nullable;

/** SQL は src/main/resources/mapper/TodoMapper.xml。 */
@Mapper
public interface TodoMapper {

    List<Todo> findAll(@Param("filter") TodoFilter filter, @Param("keyword") @Nullable String keyword);

    Optional<Todo> findById(long id);

    long countByDone(boolean done);

    void insert(Todo todo);

    int update(Todo todo);

    int deleteById(long id);

    int deleteCompleted();
}
