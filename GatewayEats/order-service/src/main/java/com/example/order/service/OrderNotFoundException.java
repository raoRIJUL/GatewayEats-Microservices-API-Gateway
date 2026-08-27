package com.example.order.service;

public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(long id) { super("Order " + id + " was not found"); }
}
