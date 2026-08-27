CREATE DATABASE IF NOT EXISTS restaurant_db;
CREATE DATABASE IF NOT EXISTS order_db;

GRANT ALL PRIVILEGES ON user_db.* TO 'food_app'@'%';
GRANT ALL PRIVILEGES ON restaurant_db.* TO 'food_app'@'%';
GRANT ALL PRIVILEGES ON order_db.* TO 'food_app'@'%';
FLUSH PRIVILEGES;
