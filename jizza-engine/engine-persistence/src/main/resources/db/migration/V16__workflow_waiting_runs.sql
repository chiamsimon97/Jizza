-- A run can now wait without anyone owing it an answer: a wait step until its time, or a
-- sub-workflow or for-each step until the workflows it started finish. Everything else about the
-- wait lives in workflow_runs.context, so this is the only schema change.
alter table workflow_runs drop constraint if exists workflow_runs_status_check;
alter table workflow_runs add constraint workflow_runs_status_check
  check (status in ('running','paused_waiting_instruction','waiting','done','failed','cancelled'));
