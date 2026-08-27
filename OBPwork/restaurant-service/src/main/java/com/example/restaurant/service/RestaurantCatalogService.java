package com.example.restaurant.service;

import com.example.restaurant.persistence.FoodItemEntity;
import com.example.restaurant.persistence.FoodItemRepository;
import com.example.restaurant.persistence.RestaurantEntity;
import com.example.restaurant.persistence.RestaurantRepository;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RestaurantCatalogService {

    private final RestaurantRepository restaurants;
    private final FoodItemRepository foodItems;

    public RestaurantCatalogService(RestaurantRepository restaurants, FoodItemRepository foodItems) {
        this.restaurants = restaurants;
        this.foodItems = foodItems;
    }

    public RestaurantView createRestaurant(long ownerUserId, String name, String address) {
        RestaurantEntity restaurant = new RestaurantEntity(
                ownerUserId, requireText(name, "name", 120), requireText(address, "address", 300));
        return restaurantView(restaurants.saveAndFlush(restaurant));
    }

    @Transactional(readOnly = true)
    public List<RestaurantView> allRestaurants() {
        return restaurants.findAll(Sort.by("id")).stream().map(this::restaurantView).toList();
    }

    @Transactional(readOnly = true)
    public RestaurantView restaurant(long id) {
        return restaurantView(findRestaurant(id));
    }

    @Transactional
    public FoodItemView addFoodItem(long ownerUserId, long restaurantId, String name,
                                    String description, BigDecimal price, boolean available) {
        RestaurantEntity restaurant = findRestaurant(restaurantId);
        requireOwner(restaurant, ownerUserId);
        FoodItemEntity item = new FoodItemEntity(restaurant,
                requireText(name, "name", 120), requireText(description, "description", 500),
                requirePrice(price), available);
        return foodItemView(foodItems.saveAndFlush(item));
    }

    @Transactional(readOnly = true)
    public List<FoodItemView> foodItems(long restaurantId) {
        findRestaurant(restaurantId);
        return foodItems.findByRestaurantIdOrderById(restaurantId).stream()
                .map(this::foodItemView).toList();
    }

    @Transactional
    public FoodItemView updateFoodItem(long ownerUserId, long itemId, String name,
                                       String description, BigDecimal price, boolean available) {
        FoodItemEntity item = findFoodItem(itemId);
        requireOwner(item.getRestaurant(), ownerUserId);
        item.update(requireText(name, "name", 120), requireText(description, "description", 500),
                requirePrice(price), available);
        return foodItemView(foodItems.saveAndFlush(item));
    }

    @Transactional
    public void deleteFoodItem(long ownerUserId, long itemId) {
        FoodItemEntity item = findFoodItem(itemId);
        requireOwner(item.getRestaurant(), ownerUserId);
        foodItems.delete(item);
    }

    @Transactional(readOnly = true)
    public RestaurantQuote quote(long restaurantId, List<RequestedItem> requestedItems) {
        RestaurantEntity restaurant = findRestaurant(restaurantId);
        if (!restaurant.isActive()) {
            throw new IllegalArgumentException("Restaurant " + restaurantId + " is not accepting orders");
        }
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("At least one food item is required");
        }
        var uniqueIds = new HashSet<Long>();
        for (RequestedItem item : requestedItems) {
            if (item.foodItemId() <= 0 || item.quantity() <= 0) {
                throw new IllegalArgumentException("Food item IDs and quantities must be positive");
            }
            if (!uniqueIds.add(item.foodItemId())) {
                throw new IllegalArgumentException("Duplicate food item " + item.foodItemId());
            }
        }

        Map<Long, FoodItemEntity> storedItems = foodItems.findAllById(uniqueIds).stream()
                .collect(Collectors.toMap(FoodItemEntity::getId, Function.identity()));
        List<QuotedItem> quotedItems = requestedItems.stream().map(requested -> {
            FoodItemEntity item = storedItems.get(requested.foodItemId());
            if (item == null || item.getRestaurant().getId() != restaurantId) {
                throw new FoodItemNotFoundException(requested.foodItemId());
            }
            if (!item.isAvailable()) {
                throw new IllegalArgumentException("Food item " + item.getId() + " is unavailable");
            }
            return new QuotedItem(item.getId(), item.getName(), item.getPrice(), requested.quantity());
        }).toList();

        return new RestaurantQuote(restaurant.getId(), restaurant.getOwnerUserId(), quotedItems);
    }

    private RestaurantEntity findRestaurant(long id) {
        return restaurants.findById(id).orElseThrow(() -> new RestaurantNotFoundException(id));
    }

    private FoodItemEntity findFoodItem(long id) {
        return foodItems.findById(id).orElseThrow(() -> new FoodItemNotFoundException(id));
    }

    private void requireOwner(RestaurantEntity restaurant, long userId) {
        if (restaurant.getOwnerUserId() != userId) {
            throw new AccessDeniedException("Only the restaurant owner may change this resource");
        }
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) throw new IllegalArgumentException(field + " is too long");
        return trimmed;
    }

    private BigDecimal requirePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0 || price.scale() > 2 || price.precision() > 12) {
            throw new IllegalArgumentException("price must be positive with at most 2 decimal places");
        }
        return price;
    }

    private RestaurantView restaurantView(RestaurantEntity restaurant) {
        return new RestaurantView(restaurant.getId(), restaurant.getOwnerUserId(), restaurant.getName(),
                restaurant.getAddress(), restaurant.isActive(), restaurant.getCreatedAt(), restaurant.getUpdatedAt());
    }

    private FoodItemView foodItemView(FoodItemEntity item) {
        return new FoodItemView(item.getId(), item.getRestaurant().getId(), item.getName(),
                item.getDescription(), item.getPrice(), item.isAvailable(),
                item.getCreatedAt(), item.getUpdatedAt());
    }

    public record RestaurantView(long id, long ownerUserId, String name, String address,
                                 boolean active, Instant createdAt, Instant updatedAt) { }

    public record FoodItemView(long id, long restaurantId, String name, String description,
                               BigDecimal price, boolean available, Instant createdAt, Instant updatedAt) { }

    public record RequestedItem(long foodItemId, int quantity) { }

    public record QuotedItem(long foodItemId, String name, BigDecimal unitPrice, int quantity) { }

    public record RestaurantQuote(long restaurantId, long ownerUserId, List<QuotedItem> items) { }
}
