package com.example.order.api;

import com.example.order.persistence.OrderStatus;
import com.example.order.service.FoodOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final FoodOrderService orders;

    public OrderController(FoodOrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FoodOrderService.OrderView create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        List<FoodOrderService.RequestedItem> items = request.items().stream()
                .map(item -> new FoodOrderService.RequestedItem(item.foodItemId(), item.quantity()))
                .toList();
        return orders.create(userId(jwt), request.restaurantId(), items, authorization);
    }

    @GetMapping("/my")
    ResponseEntity<List<FoodOrderService.OrderView>> myOrders(@AuthenticationPrincipal Jwt jwt) {
        return noStore(orders.myOrders(userId(jwt), isRestaurantOwner(jwt)));
    }

    @GetMapping("/{id}")
    ResponseEntity<FoodOrderService.OrderView> order(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return noStore(orders.order(id, userId(jwt), isRestaurantOwner(jwt)));
    }

    @PatchMapping("/{id}/status")
    ResponseEntity<FoodOrderService.OrderView> updateStatus(
            @PathVariable long id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateStatusRequest request
    ) {
        return noStore(orders.changeStatus(id, userId(jwt), request.status()));
    }

    private long userId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        }
        catch (NumberFormatException exception) {
            throw new IllegalArgumentException("JWT subject must be a numeric user ID");
        }
    }

    private boolean isRestaurantOwner(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("RESTAURANT_OWNER");
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    public record CreateOrderRequest(
            @Min(1) long restaurantId,
            @NotEmpty @Size(max = 50) List<@Valid RequestedItemRequest> items) { }

    public record RequestedItemRequest(@Min(1) long foodItemId, @Min(1) int quantity) { }

    public record UpdateStatusRequest(@NotNull OrderStatus status) { }
}
