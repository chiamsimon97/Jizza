-- Forms: templates a person builds from fields (Basic, Advanced, or generated from a workflow's
-- steps), publishes at an unguessable public link, and whose submissions start workflows.
--
-- fields is the ordered list of the form's fields ([{id, type, name, label, ...}]); a field made
-- from a workflow step carries a binding {workflowId, nodeId, path, mode} saying which of that
-- step's settings the submitted value fills. settings holds the form's own options (submit label,
-- thank-you message). public_token is set the first time the form is published and kept after,
-- so a shared link keeps working when the form is unpublished and published again.
create table forms (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  name varchar(120) not null,
  description text,
  fields jsonb not null default '[]',
  settings jsonb not null default '{}',
  status varchar(16) not null default 'draft' check (status in ('draft', 'published')),
  public_token varchar(48) unique,
  published_at timestamptz,
  source_workflow_id uuid references workflows(id) on delete set null,
  submission_count int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index forms_user_idx on forms (user_id, updated_at desc);

-- One row per submission. runs lists the workflow runs it started ([{workflowId, runId, error}]).
create table form_submissions (
  id uuid primary key default gen_random_uuid(),
  form_id uuid not null references forms(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  number int not null,
  data jsonb not null default '{}',
  runs jsonb not null default '[]',
  submitted_at timestamptz not null default now()
);
create index form_submissions_form_idx on form_submissions (form_id, submitted_at desc);

-- A workflow starts when this form is submitted (null: no form starts it). Kept outside the
-- graph, so choosing the form changes no version and no fingerprint.
alter table workflows add column form_id uuid references forms(id) on delete set null;
create index workflows_form_idx on workflows (form_id) where form_id is not null;

-- Enabled: the person turned the workflow on for its triggers (a form, a schedule) with these
-- steps. A draft runs unattended only while its graph still has this fingerprint; an approved
-- workflow runs from its triggers unless it is disabled.
alter table workflows add column triggers_on_fingerprint text;
