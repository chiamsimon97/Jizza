-- Additions the Architecture Contract §1 does not name but the Blueprint requires. Each is recorded
-- as a Contract gap in /docs/INTEGRATION-MANIFEST.md rather than being folded silently into V1.

-- R42 durable Postgres job queue driving the scheduler and workflow workers.
create table job_queue (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  type varchar not null,
  payload jsonb,
  status varchar not null default 'ready'
     check (status in ('ready','leased','done','failed','dead_letter')),
  run_after timestamptz not null default now(),
  attempts int not null default 0,
  max_attempts int not null default 5,
  last_error text,
  leased_by varchar, leased_until timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index on job_queue (status, run_after);

-- R30 distributed lock so a scheduled task never double-fires under two workers (ShedLock).
create table shedlock (
  name varchar(64) primary key,
  lock_until timestamptz not null,
  locked_at timestamptz not null,
  locked_by varchar(255) not null
);

-- R50 transactional outbox so notifications and side effects emit reliably.
create table outbox (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  aggregate varchar not null, aggregate_id uuid,
  event_type varchar not null, payload jsonb not null,
  published boolean not null default false,
  created_at timestamptz not null default now(), published_at timestamptz
);
create index on outbox (published, created_at);

-- R49 LLM token accounting and budgets.
create table llm_usage (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  model varchar not null, purpose varchar,
  input_tokens int not null default 0, output_tokens int not null default 0,
  cost_units numeric(12,6) not null default 0,
  created_at timestamptz not null default now()
);
create index on llm_usage (user_id, created_at);

-- R24 single-use, expiring OAuth state bound to the session, plus the PKCE verifier (encrypted).
create table oauth_states (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  connection_id uuid not null references connections(id) on delete cascade,
  session_id uuid,
  state varchar unique not null,
  code_verifier bytea,
  redirect_uri text,
  expires_at timestamptz not null,
  used boolean not null default false,
  created_at timestamptz not null default now()
);

-- R6 secure password reset.
create table password_reset_tokens (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  token_hash varchar not null,
  expires_at timestamptz not null,
  used boolean not null default false,
  created_at timestamptz not null default now()
);

-- Hardening of Contract tables.
alter table users add column locked_until timestamptz;
alter table users add column last_login_at timestamptz;

alter table sessions add column step_up_at timestamptz;      -- R4 freshness of the last step-up
alter table sessions add column last_seen_at timestamptz not null default now();

alter table connections add column base_url text;            -- R8 declared host for the egress guard
alter table connections add column updated_at timestamptz not null default now();

alter table scheduled_tasks add column repetitive boolean not null default false;   -- R34
alter table scheduled_tasks add column catch_up_policy varchar not null default 'run_once'
  check (catch_up_policy in ('run_once','skip','run_all'));                          -- R30
alter table scheduled_tasks add column created_at timestamptz not null default now();
alter table scheduled_tasks add column version bigint not null default 0;            -- R50 optimistic lock

alter table workflows add column disabled boolean not null default false;            -- R36
alter table workflows add column review_feedback text;
alter table workflows add column graph_fingerprint text;                             -- R36 binding proof
alter table workflows add column version_lock bigint not null default 0;             -- R50 optimistic lock

alter table workflow_versions add column graph_fingerprint text;

alter table workflow_runs add column bound_task_id uuid references scheduled_tasks(id);
alter table workflow_runs add column error text;
alter table workflow_runs add column dead_lettered boolean not null default false;
alter table workflow_runs add column version_lock bigint not null default 0;

alter table workflow_run_steps add column started_at timestamptz not null default now();
alter table workflow_run_steps add column error text;
-- exactly-once: at most one terminal checkpoint per (run, node)
create unique index workflow_run_steps_run_node_uk on workflow_run_steps (run_id, node_id);

alter table notifications add column reminder_count int not null default 0;
alter table notifications add column last_reminded_at timestamptz;
create index on notifications (user_id, status);

alter table knowledge_entries add column version bigint not null default 0;          -- R50 optimistic lock

alter table idempotency_keys add column status varchar not null default 'in_flight'
  check (status in ('in_flight','completed','failed'));

alter table files add column sha256 varchar;
