package com.example.user.service;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(long id) {
        super("User " + id + " was not found");
    }
}
