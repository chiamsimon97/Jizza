-- Deleting a workflow keeps its row (its runs, run history and audit entries still point at it)
-- and hides it everywhere else.
alter table workflows add column deleted_at timestamptz;
