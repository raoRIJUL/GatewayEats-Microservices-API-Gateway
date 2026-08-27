package com.example.order.service;

public class InvalidOrderStatusException extends RuntimeException {
    public InvalidOrderStatusException(String message) { super(message); }
}
