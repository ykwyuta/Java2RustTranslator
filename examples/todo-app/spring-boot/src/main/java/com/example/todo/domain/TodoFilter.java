package com.example.todo.domain;

import org.jspecify.annotations.Nullable;

/** 一覧の絞り込み。クエリパラメータ filter=all|active|completed に対応する。 */
public enum TodoFilter {
    ALL,
    ACTIVE,
    COMPLETED;

    /** 不正な値や未指定は ALL として扱う。 */
    public static TodoFilter fromParam(@Nullable String value) {
        if (value == null) {
            return ALL;
        }
        for (TodoFilter filter : values()) {
            if (filter.name().equalsIgnoreCase(value)) {
                return filter;
            }
        }
        return ALL;
    }

    public String param() {
        return name().toLowerCase();
    }
}
