-- Resize the status column to 50 characters (sufficient for all enum values)
-- and add a CHECK constraint allowing only valid ContainerStatus values.
-- This enforces data integrity at the DB level, not only at the Java level.

ALTER TABLE container
    ALTER COLUMN status TYPE VARCHAR(50);

ALTER TABLE container
    ADD CONSTRAINT chk_container_status
        CHECK (status IN ('IN_TRANSIT', 'AT_PORT', 'CUSTOMS', 'RELEASED', 'DELIVERED'));
