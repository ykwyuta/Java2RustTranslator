package com.example.catalog.service;

import com.example.catalog.domain.Category;
import com.example.catalog.domain.Item;
import com.example.catalog.mapper.ItemMapper;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(noRollbackFor = ItemNotFoundException.class)
public class ItemService {

    private final ItemMapper itemMapper;
    private final AuditService auditService;
    private final PricingService pricingService;

    public ItemService(ItemMapper itemMapper, AuditService auditService, PricingService pricingService) {
        this.itemMapper = itemMapper;
        this.auditService = auditService;
        this.pricingService = pricingService;
    }

    @Transactional(readOnly = true)
    public List<Item> search(@Nullable String keyword, List<Category> categories) {
        return itemMapper.search(keyword, categories, "price DESC, id");
    }

    @Transactional(readOnly = true)
    public List<Item> byIds(List<Long> ids) {
        return itemMapper.findByIds(ids);
    }

    public int importAll(List<Item> items) {
        int count = itemMapper.insertAll(items);
        auditService.record("import", null);
        return count;
    }

    public Item discount(long id, int percent) throws SoldOutException {
        Item item = itemMapper.findById(id);
        if (item == null) {
            throw new ItemNotFoundException(id);
        }
        if (item.getPrice() <= 0) {
            throw new SoldOutException(id);
        }
        pricingService.discount(item, percent);
        auditService.record("discount", item.getId());
        return item;
    }

    @Transactional(readOnly = true)
    public @Nullable Category categoryOf(long id) {
        return itemMapper.categoryOf(id);
    }
}
