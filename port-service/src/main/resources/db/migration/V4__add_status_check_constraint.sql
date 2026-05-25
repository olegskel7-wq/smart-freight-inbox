-- Shrink the column to 50 characters (enough for every enum value)
-- and add a CHECK constraint allowing only valid ContainerStatus values.
-- This enforces data integrity at the database level, not only in Java.

ALTER TABLE container
    ALTER COLUMN status TYPE VARCHAR(50);

ALTER TABLE container
    ADD CONSTRAINT chk_container_status
        CHECK (status IN ('IN_TRANSIT', 'AT_PORT', 'CUSTOMS', 'RELEASED', 'DELIVERED'));
