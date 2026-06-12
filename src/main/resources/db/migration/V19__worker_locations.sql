-- Fix and fill location text for all workers based on their coordinates.
-- Applies unconditionally so it also corrects workers whose location field
-- was seeded with wrong city names in earlier hand-entered data.

UPDATE worker SET location = 'Sofia, Bulgaria'        WHERE lat BETWEEN 42.68 AND 42.72  AND lng BETWEEN 23.30 AND 23.35;
UPDATE worker SET location = 'Plovdiv, Bulgaria'      WHERE lat BETWEEN 42.12 AND 42.17  AND lng BETWEEN 24.73 AND 24.78;
UPDATE worker SET location = 'Burgas, Bulgaria'       WHERE lat BETWEEN 42.48 AND 42.52  AND lng BETWEEN 27.45 AND 27.50;
UPDATE worker SET location = 'Varna, Bulgaria'        WHERE lat BETWEEN 43.19 AND 43.23  AND lng BETWEEN 27.89 AND 27.94;
UPDATE worker SET location = 'Stara Zagora, Bulgaria' WHERE lat BETWEEN 42.41 AND 42.44  AND lng BETWEEN 25.61 AND 25.66;
UPDATE worker SET location = 'Pleven, Bulgaria'       WHERE lat BETWEEN 43.40 AND 43.43  AND lng BETWEEN 24.59 AND 24.63;
UPDATE worker SET location = 'Sliven, Bulgaria'       WHERE lat BETWEEN 42.67 AND 42.71  AND lng BETWEEN 26.29 AND 26.34;
UPDATE worker SET location = 'Dobrich, Bulgaria'      WHERE lat BETWEEN 43.55 AND 43.58  AND lng BETWEEN 27.81 AND 27.85;
UPDATE worker SET location = 'Targovishte, Bulgaria'  WHERE lat BETWEEN 43.25 AND 43.28  AND lng BETWEEN 26.90 AND 26.93;
UPDATE worker SET location = 'Blagoevgrad, Bulgaria'  WHERE lat BETWEEN 41.98 AND 42.05  AND lng BETWEEN 23.08 AND 23.12;
