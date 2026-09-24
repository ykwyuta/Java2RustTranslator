package com.example.catalog.mapper;

import com.example.catalog.domain.Category;
import com.example.catalog.domain.Item;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.jspecify.annotations.Nullable;

/** 動的 SQL（mapper/ItemMapper.xml）と、注釈の &lt;script&gt;・@Results を使う Mapper。 */
@Mapper
public interface ItemMapper {

    List<Item> findByIds(@Param("ids") List<Long> ids);

    List<Item> search(@Param("keyword") @Nullable String keyword, @Param("categories") List<Category> categories,
                      @Param("orderBy") String orderBy);

    int insertAll(List<Item> items);

    int updateSelective(Item item);

    @Select("<script>SELECT id AS item_id, name, category, price, note FROM items"
            + "<where><if test='category != null'>category = #{category}</if></where> ORDER BY id</script>")
    @Results(id = "itemRow", value = {@Result(property = "id", column = "item_id", id = true)})
    List<Item> findByCategory(@Param("category") @Nullable Category category);

    @Select("SELECT id AS item_id, name, category, price, note FROM items WHERE id = #{id}")
    @ResultMap("itemRow")
    @Nullable Item findById(long id);

    @Select("SELECT category FROM items WHERE id = #{id}")
    @Nullable Category categoryOf(long id);
}
