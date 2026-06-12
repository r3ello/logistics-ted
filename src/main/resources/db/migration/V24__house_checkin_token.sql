-- Add checkin_token to house for QR-based worker attendance.
-- Token is a 64-char hex string, unique per house, never changes.
ALTER TABLE house ADD COLUMN checkin_token VARCHAR(64) UNIQUE;

UPDATE house SET checkin_token = md5(CAST(id AS TEXT) || '-checkin-tedhouse-2026') || md5(CAST(id AS TEXT) || '-salt2');
