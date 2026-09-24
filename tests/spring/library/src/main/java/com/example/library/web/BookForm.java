package com.example.library.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** 蔵書の登録フォーム。 */
public class BookForm {
    @NotBlank(message = "title is required")
    @Size(max = 200)
    private @Nullable String title;

    private @Nullable String author;

    @NotNull
    @Min(0)
    private @Nullable Integer stock;

    public String getTitle() {
        return title == null ? "" : title;
    }

    public void setTitle(@Nullable String title) {
        this.title = title;
    }

    public @Nullable String getAuthor() {
        return author;
    }

    public void setAuthor(@Nullable String author) {
        this.author = author;
    }

    public int getStock() {
        return stock == null ? 0 : stock;
    }

    public void setStock(@Nullable Integer stock) {
        this.stock = stock;
    }
}
