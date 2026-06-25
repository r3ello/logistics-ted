-- Workers no longer have a directly assigned house.
-- House is derived from the crew they belong to.
-- Null out any direct house_id assignments so the column is vestigial.
UPDATE worker SET house_id = NULL WHERE house_id IS NOT NULL;
