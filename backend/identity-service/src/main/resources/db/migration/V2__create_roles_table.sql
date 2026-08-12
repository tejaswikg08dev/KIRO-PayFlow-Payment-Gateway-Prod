-- Roles are stored as enum strings in the users table.
-- This migration exists for extensibility if role management becomes more complex.
CREATE TABLE roles (
    id SERIAL PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE,
    description VARCHAR(255)
);

INSERT INTO roles (name, description) VALUES ('USER', 'Regular end-user');
INSERT INTO roles (name, description) VALUES ('MERCHANT', 'Merchant who accepts payments');
INSERT INTO roles (name, description) VALUES ('ADMIN', 'Platform administrator');
