-- PayFlow Payment Gateway - Database Initialization
-- Creates separate databases for each microservice

CREATE DATABASE payflow_identity;
CREATE DATABASE payflow_merchant;
CREATE DATABASE payflow_payment;
CREATE DATABASE payflow_settlement;

-- Grant all privileges to the payflow user
GRANT ALL PRIVILEGES ON DATABASE payflow_identity TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_merchant TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_payment TO payflow;
GRANT ALL PRIVILEGES ON DATABASE payflow_settlement TO payflow;

-- Connect to each database and grant schema privileges
\c payflow_identity
GRANT ALL ON SCHEMA public TO payflow;

\c payflow_merchant
GRANT ALL ON SCHEMA public TO payflow;

\c payflow_payment
GRANT ALL ON SCHEMA public TO payflow;

\c payflow_settlement
GRANT ALL ON SCHEMA public TO payflow;
