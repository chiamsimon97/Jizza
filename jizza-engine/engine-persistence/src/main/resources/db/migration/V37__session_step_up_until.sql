-- How long one password confirmation unlocks sensitive actions for this session, when the person
-- chose a window ("Auto-approve for 15 min / 1 hr / 3 hr"). Null means the configured freshness
-- counted from step_up_at.
alter table sessions add column step_up_until timestamptz;
