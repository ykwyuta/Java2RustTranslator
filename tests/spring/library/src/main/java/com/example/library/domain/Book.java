package com.example.library.domain;

import org.jspecify.annotations.Nullable;

/** 蔵書。 */
public class Book {
    private Long id;
    private String title;
    private @Nullable String author;
    private int stock;
    private boolean archived;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public @Nullable String getAuthor() {
        return author;
    }

    public void setAuthor(@Nullable String author) {
        this.author = author;
    }

    public int getStock() {
        return stock;
    }

    public void setStock(int stock) {
        this.stock = stock;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    /** 在庫があって、アーカイブされていない。 */
    public boolean isAvailable() {
        return stock > 0 && !archived;
    }
}
