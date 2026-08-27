package com.example.order.persistence;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FoodOrderRepository extends JpaRepository<FoodOrderEntity, Long> {

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<FoodOrderEntity> findById(Long id);

    @EntityGraph(attributePaths = "items")
    List<FoodOrderEntity> findByUserIdOrderByCreatedAtDesc(long userId);

    @EntityGraph(attributePaths = "items")
    List<FoodOrderEntity> findByRestaurantOwnerUserIdOrderByCreatedAtDesc(long ownerUserId);
}
