-- Same version and name as the shipped V1 with different content, as if someone edited an
-- applied migration. Flyway's checksum validation must refuse to start on it.
CREATE TABLE IF NOT EXISTS nexora_executions (execution_id VARCHAR(36) PRIMARY KEY);
