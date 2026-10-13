-- Deliberately invalid: proves a failing migration aborts JdbcExecutionStore construction.
CREATE TABLE nexora_broken (id VARCHAR(36) PRIMARY KEY,;
