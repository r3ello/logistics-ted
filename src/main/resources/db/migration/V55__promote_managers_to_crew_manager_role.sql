-- V46 renamed manager workers by hardcoded ID but never changed their role.
-- On a fresh DB those workers are CREW_LEADER (created by V39 loop).
-- The org-chart API queries by role = CREW_MANAGER, so they were invisible.
-- Promote all workers that now carry a manager name to CREW_MANAGER role.

UPDATE worker SET role = 'CREW_MANAGER'
WHERE name IN (
    'Георги Димитров',
    'Петър Стоянов',
    'Мария Иванова',
    'Стефан Колев',
    'Елена Николова'
)
AND role != 'CREW_MANAGER';
