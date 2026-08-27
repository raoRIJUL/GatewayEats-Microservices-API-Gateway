CREATE TABLE food_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    restaurant_owner_user_id BIGINT NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_food_orders PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE INDEX idx_orders_user ON food_orders (user_id, created_at);
CREATE INDEX idx_orders_restaurant_owner ON food_orders (restaurant_owner_user_id, created_at);

CREATE TABLE order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    food_item_id BIGINT NOT NULL,
    food_item_name VARCHAR(120) NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    quantity INT NOT NULL,
    line_total DECIMAL(12, 2) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order
        FOREIGN KEY (order_id) REFERENCES food_orders (id) ON DELETE CASCADE
) ENGINE = InnoDB;

CREATE INDEX idx_order_items_order ON order_items (order_id);
