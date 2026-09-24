package com.example.todo.mapper;

import com.example.todo.domain.Activity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL は src/main/resources/mapper/ActivityMapper.xml。 */
@Mapper
public interface ActivityMapper {

    List<Activity> findRecent(int limit);

    void insert(Activity activity);

    void insertAll(@Param("activities") List<Activity> activities);
}
