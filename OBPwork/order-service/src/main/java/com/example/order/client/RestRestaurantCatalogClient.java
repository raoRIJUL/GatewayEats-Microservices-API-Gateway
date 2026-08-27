package com.example.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;
import java.util.List;

@Component
public class RestRestaurantCatalogClient implements RestaurantCatalogClient {

    private final RestClient restClient;

    public RestRestaurantCatalogClient(
            @Value("${services.restaurant.base-url:http://localhost:8081}") String baseUrl
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RestaurantQuote quote(long restaurantId, List<RequestedItem> items, String authorizationHeader) {
        try {
            RestaurantQuote quote = restClient.post()
                    .uri("/internal/restaurants/{id}/quote", restaurantId)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .body(new QuoteRequest(items))
                    .retrieve()
                    .body(RestaurantQuote.class);
            if (quote == null) {
                throw new RestaurantServiceUnavailableException(
                        "Restaurant Service returned an empty quote", null);
            }
            return quote;
        }
        catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new IllegalArgumentException("Restaurant or food item cannot be ordered");
            }
            throw new RestaurantServiceUnavailableException("Restaurant Service is temporarily unavailable", exception);
        }
        catch (ResourceAccessException exception) {
            throw new RestaurantServiceUnavailableException("Restaurant Service is temporarily unavailable", exception);
        }
    }

    private record QuoteRequest(List<RequestedItem> items) { }
}
