-- =========================================================
-- V7: Plants are archived (soft-deleted), never hard-deleted.
-- A customer's historical bookings against a plant must keep working and
-- displaying correctly even after they remove that plant/site from their
-- account, so we never physically delete a plant row anymore.
-- =========================================================

ALTER TABLE plants ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE plants ADD COLUMN deleted_at TIMESTAMP NULL;

CREATE INDEX idx_plants_deleted ON plants(deleted);

-- Bookings referencing a plant must never be cascade-deleted just because
-- the plant is removed from the customer's active list — with soft delete
-- in place at the application layer, a hard delete of a plant should now
-- be refused outright rather than silently wiping booking history.
ALTER TABLE bookings DROP CONSTRAINT IF EXISTS bookings_plant_id_fkey;
ALTER TABLE bookings ADD CONSTRAINT bookings_plant_id_fkey
    FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT;
