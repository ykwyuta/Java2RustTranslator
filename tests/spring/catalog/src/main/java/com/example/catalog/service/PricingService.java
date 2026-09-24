package com.example.catalog.service;

import com.example.catalog.domain.Item;
import com.example.catalog.mapper.ItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 呼び出し元のトランザクションに参加する（propagation = REQUIRED）。 */
@Service
@Transactional
public class PricingService {

    private final ItemMapper itemMapper;

    public PricingService(ItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    public int discount(Item item, int percent) {
        item.setPrice(item.getPrice() * (100 - percent) / 100);
        itemMapper.updateSelective(item);
        return item.getPrice();
    }
}
