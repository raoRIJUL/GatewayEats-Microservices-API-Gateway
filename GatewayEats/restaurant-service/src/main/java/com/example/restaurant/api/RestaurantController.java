package com.example.restaurant.api;

import com.example.restaurant.service.RestaurantCatalogService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@RestController
public class RestaurantController {

    private static final CacheControl PUBLIC_CACHE =
            CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    private final RestaurantCatalogService catalog;

    public RestaurantController(RestaurantCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/restaurants")
    @ResponseStatus(HttpStatus.CREATED)
    RestaurantCatalogService.RestaurantView createRestaurant(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RestaurantRequest request
    ) {
        return catalog.createRestaurant(userId(jwt), request.name(), request.address());
    }

    @GetMapping("/restaurants")
    ResponseEntity<List<RestaurantCatalogService.RestaurantView>> restaurants() {
        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(catalog.allRestaurants());
    }

    @GetMapping("/restaurants/{id}")
    ResponseEntity<RestaurantCatalogService.RestaurantView> restaurant(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(catalog.restaurant(id));
    }

    @PostMapping("/restaurants/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    RestaurantCatalogService.FoodItemView addItem(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FoodItemRequest request
    ) {
        return catalog.addFoodItem(userId(jwt), id, request.name(), request.description(),
                request.price(), request.available() == null || request.available());
    }

    @GetMapping("/restaurants/{id}/items")
    ResponseEntity<List<RestaurantCatalogService.FoodItemView>> items(@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(catalog.foodItems(id));
    }

    @PutMapping("/items/{id}")
    RestaurantCatalogService.FoodItemView updateItem(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FoodItemRequest request
    ) {
        return catalog.updateFoodItem(userId(jwt), id, request.name(), request.description(),
                request.price(), request.available() == null || request.available());
    }

    @DeleteMapping("/items/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteItem(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        catalog.deleteFoodItem(userId(jwt), id);
    }

    @PostMapping("/internal/restaurants/{id}/quote")
    RestaurantCatalogService.RestaurantQuote quote(
            @PathVariable long id,
            @Valid @RequestBody QuoteRequest request
    ) {
        List<RestaurantCatalogService.RequestedItem> items = request.items().stream()
                .map(item -> new RestaurantCatalogService.RequestedItem(item.foodItemId(), item.quantity()))
                .toList();
        return catalog.quote(id, items);
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JWT subject must be a numeric user ID");
        }
    }

    public record RestaurantRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 300) String address) { }

    public record FoodItemRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 500) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price,
            Boolean available) { }

    public record QuoteRequest(@NotEmpty @Size(max = 50) List<@Valid RequestedItemRequest> items) { }

    public record RequestedItemRequest(
            @Min(1) long foodItemId,
            @Min(1) int quantity) { }
}
