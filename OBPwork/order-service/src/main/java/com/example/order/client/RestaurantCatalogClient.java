package com.example.order.client;

import java.math.BigDecimal;
import java.util.List;

public interface RestaurantCatalogClient {

    RestaurantQuote quote(long restaurantId, List<RequestedItem> items, String authorizationHeader);

    record RequestedItem(long foodItemId, int quantity) { }

    record QuotedItem(long foodItemId, String name, BigDecimal unitPrice, int quantity) { }

    record RestaurantQuote(long restaurantId, long ownerUserId, List<QuotedItem> items) { }
}
