package com.example.library.mapper;

import com.example.library.domain.Book;
import com.example.library.domain.Sort;
import com.example.library.domain.StockSummary;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

/** 注釈の SQL と XML（mapper/BookMapper.xml）の両方を使う Mapper。 */
@Mapper
public interface BookMapper {

    @Select("SELECT id, title, author, stock, archived FROM books WHERE id = #{id}")
    @Nullable Book findById(long id);

    @Insert({"INSERT INTO books (title, author, stock, archived)", "VALUES (#{title}, #{author}, #{stock}, #{archived})"})
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Book book);

    List<Book> search(@Param("keyword") @Nullable String keyword, @Param("inStockOnly") boolean inStockOnly,
                      @Param("sort") Sort sort);

    List<Long> findIdsByAuthor(@Param("author") String author);

    int updateSelective(Book book);

    boolean archive(long id);

    @Select("SELECT COUNT(*) AS books, COALESCE(SUM(stock), 0) AS total_stock FROM books WHERE archived = FALSE")
    StockSummary summary();
}
