package com.example.restaurant.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FoodItemRepository extends JpaRepository<FoodItemEntity, Long> {
    List<FoodItemEntity> findByRestaurantIdOrderById(long restaurantId);
}
