package com.example.restaurant;

import com.example.restaurant.service.RestaurantCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:restaurants;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional
class RestaurantServiceIntegrationTest {

    @Autowired private RestaurantCatalogService catalog;

    @Test
    void ownerCreatesMenuAndOrderServiceReceivesTrustedQuote() {
        var restaurant = catalog.createRestaurant(10L, "Spice House", "Main Street");
        var item = catalog.addFoodItem(10L, restaurant.id(), "Paneer Bowl",
                "Paneer, rice and vegetables", new BigDecimal("12.50"), true);

        var quote = catalog.quote(restaurant.id(),
                List.of(new RestaurantCatalogService.RequestedItem(item.id(), 2)));

        assertThat(quote.ownerUserId()).isEqualTo(10L);
        assertThat(quote.items()).singleElement().satisfies(quoted -> {
            assertThat(quoted.name()).isEqualTo("Paneer Bowl");
            assertThat(quoted.unitPrice()).isEqualByComparingTo("12.50");
            assertThat(quoted.quantity()).isEqualTo(2);
        });
        assertThatThrownBy(() -> catalog.updateFoodItem(99L, item.id(), "Changed",
                "Not allowed", new BigDecimal("15.00"), true))
                .isInstanceOf(AccessDeniedException.class);
    }
}
