-- Web recordings are captured by a browser device, then compiled into ordinary workflows.
-- Recorded step data is encrypted by the application before it is stored.
alter table devices drop constraint if exists devices_platform_check;
alter table devices add constraint devices_platform_check
  check (platform in ('windows','macos','linux','android','ios','browser','other'));

alter table workflows drop constraint if exists workflows_origin_check;
alter table workflows add constraint workflows_origin_check
  check (origin in ('user_built','agent_authored','recorded'));

create table web_recordings (
  id uuid primary key,
  owner_id uuid not null references users(id) on delete cascade,
  workspace_id uuid references workspaces(id) on delete cascade,
  device_id uuid references devices(id) on delete set null,
  name varchar(120) not null,
  start_url text not null,
  status varchar(16) not null
    check (status in ('recording','review','saved','discarded','failed')),
  command_id uuid references device_commands(id) on delete set null,
  workflow_id uuid references workflows(id) on delete set null,
  sites text[] not null default '{}',
  step_count int not null default 0,
  started_at timestamptz not null default now(),
  stopped_at timestamptz,
  updated_at timestamptz not null default now()
);
create index web_recordings_owner_idx
  on web_recordings (owner_id, status, updated_at desc);

create table web_recording_steps (
  recording_id uuid not null references web_recordings(id) on delete cascade,
  seq int not null,
  position int not null,
  step bytea not null,
  deleted boolean not null default false,
  primary key (recording_id, seq)
);

create table web_locator_fixes (
  id uuid primary key,
  owner_id uuid not null references users(id) on delete cascade,
  workflow_id uuid not null references workflows(id) on delete cascade,
  node_id varchar(64) not null,
  run_id uuid references workflow_runs(id) on delete set null,
  candidates jsonb not null,
  status varchar(12) not null check (status in ('open','applied','dismissed')),
  created_at timestamptz not null default now(),
  decided_at timestamptz
);
create unique index web_locator_fixes_open_idx
  on web_locator_fixes (workflow_id, node_id) where status = 'open';
