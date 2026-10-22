-- Values a workflow keeps between runs (the Storage step): counters, the last item seen, a list
-- to add to. One row per key per user, shared by all of that user's workflows.
create table workflow_storage (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  key varchar(200) not null,
  value jsonb,
  updated_at timestamptz not null default now(),
  unique (user_id, key)
);
