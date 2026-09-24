package com.example.library.service;

public class InvalidBookException extends RuntimeException {

    public InvalidBookException(String title) {
        super("invalid title: '" + title + "'");
    }
}
