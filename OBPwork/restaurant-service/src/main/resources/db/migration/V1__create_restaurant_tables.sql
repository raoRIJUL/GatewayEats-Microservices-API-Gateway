CREATE TABLE restaurants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    address VARCHAR(300) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_restaurants PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX idx_restaurants_owner ON restaurants (owner_user_id);

CREATE TABLE food_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL,
    price DECIMAL(12, 2) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_food_items PRIMARY KEY (id),
    CONSTRAINT fk_food_items_restaurant
        FOREIGN KEY (restaurant_id) REFERENCES restaurants (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE INDEX idx_food_items_restaurant ON food_items (restaurant_id);
