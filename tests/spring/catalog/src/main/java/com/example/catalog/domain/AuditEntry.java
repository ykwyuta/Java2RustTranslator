package com.example.catalog.domain;

import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

public class AuditEntry {

    private String action;
    private @Nullable Long itemId;
    private LocalDateTime createdAt;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public @Nullable Long getItemId() {
        return itemId;
    }

    public void setItemId(@Nullable Long itemId) {
        this.itemId = itemId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
