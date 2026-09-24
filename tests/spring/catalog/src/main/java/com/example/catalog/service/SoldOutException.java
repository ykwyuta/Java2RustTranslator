package com.example.catalog.service;

/** 検査例外（Spring の既定ではロールバックしない）。 */
public class SoldOutException extends Exception {
    public SoldOutException(long id) {
        super("Sold out: " + id);
    }
}
