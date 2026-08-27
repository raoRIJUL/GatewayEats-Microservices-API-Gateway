package com.example.order.client;

public class RestaurantServiceUnavailableException extends RuntimeException {
    public RestaurantServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
