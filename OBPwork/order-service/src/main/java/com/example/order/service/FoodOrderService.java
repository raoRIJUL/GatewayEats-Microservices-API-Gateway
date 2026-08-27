package com.example.order.service;

import com.example.order.client.RestaurantCatalogClient;
import com.example.order.persistence.FoodOrderEntity;
import com.example.order.persistence.FoodOrderRepository;
import com.example.order.persistence.OrderItemEntity;
import com.example.order.persistence.OrderStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class FoodOrderService {

    private static final Map<OrderStatus, OrderStatus> NEXT_STATUS = Map.of(
            OrderStatus.PLACED, OrderStatus.CONFIRMED,
            OrderStatus.CONFIRMED, OrderStatus.PREPARING,
            OrderStatus.PREPARING, OrderStatus.READY,
            OrderStatus.READY, OrderStatus.DELIVERED);

    private final FoodOrderRepository orders;
    private final RestaurantCatalogClient restaurantClient;

    public FoodOrderService(FoodOrderRepository orders, RestaurantCatalogClient restaurantClient) {
        this.orders = orders;
        this.restaurantClient = restaurantClient;
    }

    public OrderView create(long userId, long restaurantId, List<RequestedItem> requestedItems,
                            String authorizationHeader) {
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("At least one food item is required");
        }
        List<RestaurantCatalogClient.RequestedItem> quoteItems = requestedItems.stream()
                .map(item -> new RestaurantCatalogClient.RequestedItem(item.foodItemId(), item.quantity()))
                .toList();
        RestaurantCatalogClient.RestaurantQuote quote =
                restaurantClient.quote(restaurantId, quoteItems, authorizationHeader);
        validateQuote(restaurantId, requestedItems.size(), quote);

        BigDecimal total = quote.items().stream()
                .map(item -> item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0 || total.precision() > 12) {
            throw new IllegalArgumentException("Calculated order total is outside the supported range");
        }

        FoodOrderEntity order = new FoodOrderEntity(
                userId, quote.restaurantId(), quote.ownerUserId(), total);
        quote.items().forEach(item -> {
            BigDecimal lineTotal = item.unitPrice().multiply(BigDecimal.valueOf(item.quantity()));
            order.addItem(item.foodItemId(), item.name(), item.unitPrice(), item.quantity(), lineTotal);
        });
        return view(orders.saveAndFlush(order));
    }

    @Transactional(readOnly = true)
    public OrderView order(long orderId, long userId, boolean restaurantOwner) {
        FoodOrderEntity order = findOrder(orderId);
        requireCanRead(order, userId, restaurantOwner);
        return view(order);
    }

    @Transactional(readOnly = true)
    public List<OrderView> myOrders(long userId, boolean restaurantOwner) {
        List<FoodOrderEntity> result = restaurantOwner
                ? orders.findByRestaurantOwnerUserIdOrderByCreatedAtDesc(userId)
                : orders.findByUserIdOrderByCreatedAtDesc(userId);
        return result.stream().map(this::view).toList();
    }

    @Transactional
    public OrderView changeStatus(long orderId, long ownerUserId, OrderStatus requestedStatus) {
        FoodOrderEntity order = findOrder(orderId);
        if (order.getRestaurantOwnerUserId() != ownerUserId) {
            throw new AccessDeniedException("Only this order's restaurant owner may change its status");
        }
        OrderStatus expected = NEXT_STATUS.get(order.getStatus());
        if (expected == null || expected != requestedStatus) {
            throw new InvalidOrderStatusException(
                    "Order status may change only from " + order.getStatus() + " to " +
                            (expected == null ? "no further status" : expected));
        }
        order.changeStatus(requestedStatus);
        return view(order);
    }

    private void validateQuote(long restaurantId, int requestedItemCount,
                               RestaurantCatalogClient.RestaurantQuote quote) {
        if (quote.restaurantId() != restaurantId || quote.ownerUserId() <= 0 || quote.items() == null
                || quote.items().size() != requestedItemCount) {
            throw new IllegalArgumentException("Restaurant Service returned an invalid quote");
        }
        for (RestaurantCatalogClient.QuotedItem item : quote.items()) {
            if (item.foodItemId() <= 0 || item.name() == null || item.name().isBlank()
                    || item.name().length() > 120 || item.unitPrice() == null
                    || item.unitPrice().signum() <= 0 || item.unitPrice().scale() > 2
                    || item.unitPrice().precision() > 12 || item.quantity() <= 0) {
                throw new IllegalArgumentException("Restaurant Service returned an invalid food item quote");
            }
        }
    }

    private FoodOrderEntity findOrder(long id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    private void requireCanRead(FoodOrderEntity order, long userId, boolean restaurantOwner) {
        boolean ownsOrder = order.getUserId() == userId;
        boolean ownsRestaurant = restaurantOwner && order.getRestaurantOwnerUserId() == userId;
        if (!ownsOrder && !ownsRestaurant) {
            throw new AccessDeniedException("You may view only your own orders");
        }
    }

    private OrderView view(FoodOrderEntity order) {
        List<OrderItemView> items = order.getItems().stream().map(this::itemView).toList();
        return new OrderView(order.getId(), order.getUserId(), order.getRestaurantId(),
                order.getTotalAmount(), order.getStatus(), order.getCreatedAt(), order.getUpdatedAt(), items);
    }

    private OrderItemView itemView(OrderItemEntity item) {
        return new OrderItemView(item.getFoodItemId(), item.getFoodItemName(), item.getUnitPrice(),
                item.getQuantity(), item.getLineTotal());
    }

    public record RequestedItem(long foodItemId, int quantity) { }

    public record OrderItemView(long foodItemId, String name, BigDecimal unitPrice,
                                int quantity, BigDecimal lineTotal) { }

    public record OrderView(long id, long userId, long restaurantId, BigDecimal totalAmount,
                            OrderStatus status, Instant createdAt, Instant updatedAt,
                            List<OrderItemView> items) { }
}
