package com.example.library.web;

import com.example.library.domain.Book;
import com.example.library.domain.Sort;
import com.example.library.service.BookService;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/books")
public class BookController {
    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    @GetMapping
    public String list(
            @RequestParam(required = false) @Nullable String keyword,
            @RequestParam(name = "inStock", defaultValue = "false") boolean inStockOnly,
            Model model) {
        model.addAttribute("books", bookService.search(keyword, inStockOnly, Sort.TITLE));
        model.addAttribute("summary", bookService.summary());
        model.addAttribute("keyword", keyword);
        return "books/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable long id, Model model) {
        Book book = bookService.get(id);
        model.addAttribute("label", bookService.label(book));
        model.addAttribute("book", book);
        return "books/detail";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("bookForm", new BookForm());
        return "books/form";
    }

    @PostMapping
    public String register(@Valid @ModelAttribute("bookForm") BookForm form, BindingResult bindingResult,
                           RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            return "books/form";
        }
        Book book = bookService.register(form.getTitle(), form.getAuthor(), form.getStock());
        redirectAttributes.addFlashAttribute("notice", "registered " + book.getTitle());
        return "redirect:/books/" + book.getId();
    }

    @PostMapping("/{id}/archive")
    public String archive(@PathVariable long id, RedirectAttributes redirectAttributes) {
        boolean archived = bookService.archive(id);
        redirectAttributes.addFlashAttribute("notice", archived ? "archived" : "already archived");
        redirectAttributes.addAttribute("inStock", false);
        return "redirect:/books";
    }
}
