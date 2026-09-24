package com.example.todo.domain;

/** 優先度。データベースには名前（LOW / MEDIUM / HIGH）で保存する（MyBatis の EnumTypeHandler）。 */
public enum Priority {
    LOW,
    MEDIUM,
    HIGH
}
