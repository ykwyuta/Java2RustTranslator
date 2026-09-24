package com.example.library.web;

import com.example.library.service.BookService;
import org.springframework.stereotype.Controller;

/** Web 層はまだ変換しない（J2R-SPRING-SKIPPED）。 */
@Controller
public class BookController {
    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }
}
