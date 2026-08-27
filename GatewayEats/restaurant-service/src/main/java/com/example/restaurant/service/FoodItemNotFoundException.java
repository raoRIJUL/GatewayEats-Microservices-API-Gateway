package com.example.restaurant.service;

public class FoodItemNotFoundException extends RuntimeException {
    public FoodItemNotFoundException(long id) { super("Food item " + id + " was not found"); }
}
