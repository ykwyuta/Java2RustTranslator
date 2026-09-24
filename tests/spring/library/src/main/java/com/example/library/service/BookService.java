package com.example.library.service;

import com.example.library.domain.Book;
import com.example.library.domain.Sort;
import com.example.library.domain.StockSummary;
import com.example.library.mapper.BookMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** トランザクションはメソッドごとに付ける（クラスには付けない）。 */
@Service
public class BookService {

    private final BookMapper bookMapper;

    public BookService(BookMapper bookMapper) {
        this.bookMapper = bookMapper;
    }

    @Transactional
    public Book register(String title, @Nullable String author, int stock) {
        if (title.isBlank()) {
            throw new InvalidBookException(title);
        }
        Book book = new Book();
        book.setTitle(title.strip());
        book.setAuthor(author == null ? null : author.strip());
        book.setStock(stock);
        bookMapper.insert(book);
        return book;
    }

    @Transactional(readOnly = true)
    public List<Book> search(@Nullable String keyword, boolean inStockOnly, Sort sort) {
        return bookMapper.search(normalize(keyword), inStockOnly, sort);
    }

    /** トランザクションを張らずに読む（Mapper の呼び出しごとに自動コミット）。 */
    public Book get(long id) {
        Book book = bookMapper.findById(id);
        if (book == null) {
            throw new BookNotFoundException(id);
        }
        return book;
    }

    /** 在庫を amount 増やし、更新した冊数を返す。 */
    @Transactional
    public int restock(List<Long> ids, int amount) {
        int count = 0;
        for (long id : ids) {
            Book book = get(id);
            book.setStock(book.getStock() + amount);
            bookMapper.updateSelective(book);
            count++;
        }
        return count;
    }

    @Transactional
    public boolean archive(long id) {
        if (!bookMapper.archive(id)) {
            return false;
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<Long> booksBy(String author) {
        return bookMapper.findIdsByAuthor(author);
    }

    @Transactional(readOnly = true)
    public StockSummary summary() {
        return bookMapper.summary();
    }

    /** 画面に出す名前。 */
    public String label(Book book) {
        return book.getAuthor() == null ? book.getTitle() : book.getTitle() + " / " + book.getAuthor();
    }

    private @Nullable String normalize(@Nullable String keyword) {
        if (keyword == null) {
            return null;
        }
        String s = keyword.strip();
        return s.isEmpty() ? null : s.toLowerCase();
    }
}
