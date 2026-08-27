package com.example.order;

import com.example.order.client.RestaurantCatalogClient;
import com.example.order.persistence.OrderStatus;
import com.example.order.service.FoodOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:orders;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(OrderServiceIntegrationTest.StubRestaurantConfiguration.class)
@Transactional
class OrderServiceIntegrationTest {

    @Autowired private FoodOrderService orders;

    @Test
    void calculatesTrustedTotalAndEnforcesOwnerStatusTransitions() {
        var created = orders.create(20L, 5L,
                List.of(new FoodOrderService.RequestedItem(101L, 2)), "Bearer test-token");

        assertThat(created.userId()).isEqualTo(20L);
        assertThat(created.restaurantId()).isEqualTo(5L);
        assertThat(created.totalAmount()).isEqualByComparingTo("25.00");
        assertThat(created.status()).isEqualTo(OrderStatus.PLACED);
        assertThat(created.items()).singleElement().satisfies(item ->
                assertThat(item.name()).isEqualTo("Paneer Bowl"));

        assertThatThrownBy(() -> orders.changeStatus(created.id(), 999L, OrderStatus.CONFIRMED))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(orders.changeStatus(created.id(), 10L, OrderStatus.CONFIRMED).status())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.myOrders(20L, false)).extracting(FoodOrderService.OrderView::id)
                .containsExactly(created.id());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubRestaurantConfiguration {

        @Bean
        @Primary
        RestaurantCatalogClient restaurantCatalogClient() {
            return (restaurantId, items, authorization) -> new RestaurantCatalogClient.RestaurantQuote(
                    restaurantId,
                    10L,
                    items.stream()
                            .map(item -> new RestaurantCatalogClient.QuotedItem(
                                    item.foodItemId(), "Paneer Bowl", new BigDecimal("12.50"), item.quantity()))
                            .toList());
        }
    }
}
