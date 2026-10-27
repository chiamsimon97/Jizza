-- Paired devices: the Jizza Agent app on a person's computer or phone, or a shared machine
-- enrolled by token. Devices sign in with a key they made themselves; the server keeps only the
-- public half. See docs/agent-protocol-v1.md.
create table devices (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspaces(id) on delete cascade,
  -- Null for a workspace device enrolled with a token rather than paired by a person.
  owner_user_id uuid references users(id) on delete cascade,
  enrolled_by uuid references users(id) on delete set null,
  name varchar not null,
  platform varchar not null default 'other'
    check (platform in ('windows','macos','linux','android','ios','other')),
  os_version varchar,
  app_version varchar,
  protocol_version int,
  status varchar not null default 'active' check (status in ('active','disabled','revoked')),
  tags jsonb not null default '[]',
  key_storage varchar,
  -- The latest manifest: capabilities (with the user's local limits) and declared actions.
  manifest jsonb,
  actions jsonb not null default '[]',
  shell_enabled boolean not null default false,
  blocked_actions jsonb not null default '[]',
  last_seen_at timestamptz,
  last_ip varchar,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  revoked_at timestamptz
);
create index devices_workspace on devices (workspace_id);
create index devices_owner on devices (owner_user_id);

-- A device's public keys. One is current; a rotated key is kept, retired, for the audit trail.
create table device_keys (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  public_key text not null,
  key_storage varchar,
  created_at timestamptz not null default now(),
  retired_at timestamptz
);
create index device_keys_device on device_keys (device_id);

-- One-time pairing codes. Only a hash of the code is kept.
create table device_pairings (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  code_hash varchar not null unique,
  expires_at timestamptz not null,
  used_at timestamptz,
  used_by_device_id uuid references devices(id) on delete set null,
  created_at timestamptz not null default now()
);

-- Tokens for enrolling shared machines (servers, VMs, office PCs). Only a hash is kept.
create table device_enrollment_tokens (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspaces(id) on delete cascade,
  created_by uuid references users(id) on delete set null,
  name varchar not null,
  token_hash varchar not null unique,
  uses_left int not null,
  expires_at timestamptz not null,
  tags jsonb not null default '[]',
  revoked_at timestamptz,
  created_at timestamptz not null default now()
);
create index device_enrollment_tokens_workspace on device_enrollment_tokens (workspace_id);

-- Sign-in challenges: single use, a minute long.
create table device_challenges (
  nonce varchar primary key,
  device_id uuid not null references devices(id) on delete cascade,
  expires_at timestamptz not null
);

-- Every command is a row before it is sent, so a restart or a sleeping laptop loses nothing.
-- Inputs and outputs are envelope-encrypted.
create table device_commands (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  run_id uuid,
  node_id varchar,
  action varchar not null,
  action_version int not null default 1,
  input bytea,
  status varchar not null default 'queued' check (status in ('queued','dispatched','accepted',
    'running','succeeded','failed','rejected','expired','cancelled')),
  interactive boolean not null default false,
  idempotency_key varchar unique,
  signature text,
  nonce varchar not null,
  deadline_at timestamptz not null,
  progress text,
  output bytea,
  output_mismatch boolean not null default false,
  error_code varchar,
  error text,
  created_at timestamptz not null default now(),
  dispatched_at timestamptz,
  accepted_at timestamptz,
  finished_at timestamptz
);
create index device_commands_device_status on device_commands (device_id, status);
create index device_commands_run on device_commands (run_id);
create index device_commands_open on device_commands (deadline_at)
  where status in ('queued','dispatched','accepted','running');

-- Files a device uploaded with a result, stored through the storage provider, encrypted.
create table device_files (
  id uuid primary key default gen_random_uuid(),
  device_id uuid not null references devices(id) on delete cascade,
  command_id uuid references device_commands(id) on delete cascade,
  name varchar not null,
  content_type varchar,
  size bigint not null,
  storage_ref text not null,
  created_at timestamptz not null default now()
);
create index device_files_command on device_files (command_id);

-- Where to push a phone to wake it. Tokens are encrypted.
create table device_push_tokens (
  device_id uuid primary key references devices(id) on delete cascade,
  provider varchar not null check (provider in ('apns','fcm')),
  token bytea not null,
  updated_at timestamptz not null default now()
);

-- Diagnostic lines a device sends, already redacted on the device; kept a week.
create table device_logs (
  id bigserial primary key,
  device_id uuid not null references devices(id) on delete cascade,
  ts timestamptz not null default now(),
  level varchar not null default 'info',
  message text not null,
  command_id uuid
);
create index device_logs_device_ts on device_logs (device_id, ts desc);

-- The server's own keys for the protocol: the Ed25519 key that signs commands and the secret that
-- signs access tokens. Made on first start, encrypted with the master key.
create table device_server_keys (
  id varchar primary key,
  private_key bytea not null,
  public_key text,
  created_at timestamptz not null default now()
);
