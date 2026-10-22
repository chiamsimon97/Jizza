-- Run history: what each step was handed, and the indexes the history and activity views filter on.
alter table workflow_run_steps add column input jsonb;

create index if not exists workflow_runs_user_created_idx on workflow_runs (user_id, created_at desc);
create index if not exists audit_log_user_created_idx on audit_log (user_id, created_at desc);
