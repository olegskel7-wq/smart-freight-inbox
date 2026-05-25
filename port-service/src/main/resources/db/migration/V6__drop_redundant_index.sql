-- Drop the redundant index on container_number.
-- The UNIQUE constraint declared in V1 already creates a unique B-tree index
-- on this column, so idx_container_number is a duplicate: it wastes storage
-- and adds write overhead without improving read performance.
DROP INDEX IF EXISTS idx_container_number;
