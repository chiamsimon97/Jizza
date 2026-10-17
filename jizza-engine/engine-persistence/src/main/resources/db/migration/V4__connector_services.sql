-- Connector (Service) module. A connection row is now the definition of a Service: its metadata,
-- authentication scheme and the credentials of the user's one authenticated instance of it. Around
-- it sit the pieces a service is built from: raw APIs (generated_tools), resources, actions,
-- triggers and variables, plus a run history.

alter table connections add column slug varchar;
alter table connections add column description text;
alter table connections add column category varchar;
alter table connections add column version_status varchar not null default 'draft'
  check (version_status in ('draft','live'));
alter table connections add column live_version int not null default 0;
alter table connections add column published_at timestamptz;
update connections
   set slug = trim(both '_' from regexp_replace(lower(service_name), '[^a-z0-9]+', '_', 'g'))
         || '_' || left(replace(id::text, '-', ''), 6);
alter table connections alter column slug set not null;
create unique index on connections (user_id, slug);

-- Raw APIs: a display label and the kind of request (an ordinary call, a polling list, a webhook).
alter table generated_tools add column label varchar;
alter table generated_tools add column kind varchar not null default 'request'
  check (kind in ('request','polling','webhook'));

-- A logical entity of the service (Invoice, Contact, Bill) and the fields it carries.
create table service_resources (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references connections(id) on delete cascade,
  name varchar not null, label varchar not null, description text,
  fields jsonb not null default '[]',
  id_field varchar,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (connection_id, name)
);

-- Something an agent can do with the service, built on one API.
create table service_actions (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references connections(id) on delete cascade,
  name varchar not null, label varchar not null, description text,
  api_id uuid not null references generated_tools(id) on delete cascade,
  resource_id uuid references service_resources(id) on delete set null,
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (connection_id, name)
);

-- An event in the service, detected by polling an API for items not seen before.
create table service_triggers (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references connections(id) on delete cascade,
  name varchar not null, label varchar not null, description text,
  api_id uuid not null references generated_tools(id) on delete cascade,
  resource_id uuid references service_resources(id) on delete set null,
  kind varchar not null default 'polling' check (kind in ('polling','webhook')),
  poll_interval_minutes int not null default 15 check (poll_interval_minutes >= 1),
  items_path varchar,
  id_field varchar not null default 'id',
  enabled boolean not null default true,
  seen_ids jsonb not null default '[]',
  last_polled_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (connection_id, name)
);

-- Service-level constants, referenced from APIs as {{var.name}}. Secret values are encrypted.
create table service_variables (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references connections(id) on delete cascade,
  name varchar not null, description text,
  value text,
  secret_value bytea,
  secret boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (connection_id, name)
);

-- History: every action run, trigger poll and test against a service.
create table service_runs (
  id uuid primary key default gen_random_uuid(),
  connection_id uuid not null references connections(id) on delete cascade,
  kind varchar not null check (kind in ('action','trigger','test')),
  ref_id uuid, ref_name varchar,
  source varchar not null,
  status varchar not null check (status in ('ok','error')),
  duration_ms bigint,
  input jsonb, output jsonb, error text,
  created_at timestamptz not null default now()
);
create index on service_runs (connection_id, created_at desc);

-- Agents are granted individual service actions, the same way they are granted generated tools.
alter table agent_capabilities drop constraint agent_capabilities_capability_type_check;
alter table agent_capabilities add constraint agent_capabilities_capability_type_check
  check (capability_type in ('knowledge','generated_tool','skill','service_action'));
