package com.example.restaurant.service;

public class RestaurantNotFoundException extends RuntimeException {
    public RestaurantNotFoundException(long id) { super("Restaurant " + id + " was not found"); }
}
