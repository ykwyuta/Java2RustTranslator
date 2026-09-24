package com.example.catalog.mapper;

import com.example.catalog.domain.AuditEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuditMapper {

    @Insert("INSERT INTO audit (action, item_id, created_at) VALUES (#{action}, #{itemId}, #{createdAt})")
    void insert(AuditEntry entry);

    @Select("SELECT COUNT(*) FROM audit")
    long count();
}
