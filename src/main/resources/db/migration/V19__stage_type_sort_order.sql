-- Display position of a stage type, editable from the Stages screen (drag and drop).
--
-- stage_order is NOT renumbered: it is the stage's identity, referenced by house_stage,
-- crew_stage_type, worker_stage_type, house_stage_crew_log, qa_checklist_item, qa_inspection and
-- stage_weather_rule, hardcoded in V13 and in the frontend (stage 1 = Foundation gates the rest).
-- sort_order only decides the order stages are listed in.
--
-- Backfill reproduces the order the app used until now: by stage_order, with 'Completion' last.

ALTER TABLE public.stage_type ADD COLUMN sort_order integer;

UPDATE public.stage_type st
   SET sort_order = o.pos
  FROM (SELECT stage_order,
               ROW_NUMBER() OVER (ORDER BY CASE WHEN stage_name_en = 'Completion' THEN 1 ELSE 0 END,
                                           stage_order) AS pos
          FROM public.stage_type) o
 WHERE o.stage_order = st.stage_order;

ALTER TABLE public.stage_type ALTER COLUMN sort_order SET NOT NULL;
