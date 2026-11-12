-- Edit recording: a recording can be a saved recording (a workflow) opened again for editing in the
-- browser. Saving it writes a new version of that workflow instead of a new one.
alter table web_recordings
  add column source_workflow_id uuid references workflows(id) on delete set null,
  add column source_version int;
