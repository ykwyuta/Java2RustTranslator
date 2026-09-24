package com.example.catalog.web;

import com.example.catalog.domain.Category;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;

public class ItemForm {

    @NotBlank(message = "Enter a name")
    private @Nullable String name;

    private Category category = Category.BOOK;

    @Min(value = 0, message = "Must not be negative")
    private int price = 100;

    public @Nullable String getName() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = name;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public int getPrice() {
        return price;
    }

    public void setPrice(int price) {
        this.price = price;
    }
}
