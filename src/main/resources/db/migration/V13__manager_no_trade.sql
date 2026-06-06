-- Managers do not have a trade; clear any existing values
UPDATE worker SET trade = NULL WHERE role = 'CREW_MANAGER';
