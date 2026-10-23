-- The workflow library: what Jizza keeps about a workflow to recognise and reuse it (when to use
-- it, its inputs, and the guardrails the user set on reusing it). Null means not in the library.
alter table workflows add column reuse jsonb;

create index if not exists workflows_user_reuse_idx on workflows (user_id) where reuse is not null;
