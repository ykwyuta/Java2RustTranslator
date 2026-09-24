package com.example.library.service;

public class BookNotFoundException extends RuntimeException {

    public BookNotFoundException(long id) {
        super("book " + id + " not found");
    }
}
