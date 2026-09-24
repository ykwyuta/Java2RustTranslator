package com.example.library.domain;

/** 在庫の集計（select の結果になる record）。 */
public record StockSummary(long books, long totalStock) {

    public boolean isEmpty() {
        return books == 0;
    }
}
