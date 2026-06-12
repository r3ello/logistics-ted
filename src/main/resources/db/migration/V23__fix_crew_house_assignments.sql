-- Fix crew→house assignments so each seeded house has exactly one crew
-- and crew locations match their workers' coordinates.
--
-- Problems found:
--   Alpha Crew (id=1) workers are in Sofia but house_id was set to 2 (Plovdiv)
--   Beta  Crew (id=2) workers are in Plovdiv but house_id was set to 8 (Sliven)
--
-- Correct mapping (crew workers' city → house):
--   1 Alpha  → house  1 (Sofia Centro)
--   2 Beta   → house  2 (Plovdiv)
--   3 Gamma  → house  3 (Varna Mar)     ✓ already correct
--   4 Delta  → house  4 (Burgas)        ✓ already correct
--   5 Echo   → house  6 (Stara Zagora)  ✓ already correct
--   6 Zeta   → house  7 (Pleven)        ✓ already correct
--   7 Eta    → house  8 (Sliven)        ✓ already correct
--   8 Theta  → house  9 (Dobrich)       ✓ already correct
--   9 Junga  → house 10 (Shumen)        ✓ already correct

UPDATE crew SET house_id = 1 WHERE id = 1;
UPDATE crew SET house_id = 2 WHERE id = 2;
