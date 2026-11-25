-- Kai Portfolio, Phase 1a (3 of 3): Money Health runs, the action plan, life events and reviews.
--
-- A health run keeps the checks it computed and the configuration it used, so an old score can be
-- explained later. An action item is unique per key while it is still live (open, later or
-- snoozed); done and dismissed rows stay as history.

create table fin_health_check_run (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  score numeric(5,1),
  checks jsonb not null default '[]'::jsonb,
  config_snapshot jsonb not null default '{}'::jsonb,
  run_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index fin_health_check_run_user_idx on fin_health_check_run (user_id, run_at desc);

create table fin_action_item (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid references fin_person(id) on delete cascade,
  action_key varchar(80) not null,
  layer varchar(14) not null,
  title varchar(200) not null,
  reason text,
  impact_points numeric(5,1) not null default 0,
  rank int not null default 0,
  details jsonb not null default '{}'::jsonb,
  status varchar(10) not null default 'open',
  snoozed_until date,
  dismiss_reason varchar(200),
  source_check varchar(40),
  due_date date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_action_item_layer_check
    check (layer in ('survival', 'important', 'aspirational', 'hygiene')),
  constraint fin_action_item_status_check
    check (status in ('open', 'later', 'done', 'snoozed', 'dismissed'))
);
create index fin_action_item_user_idx on fin_action_item (user_id);
create unique index fin_action_item_live on fin_action_item (user_id, action_key)
  where status in ('open', 'later', 'snoozed');

create table fin_life_event (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid references fin_person(id) on delete cascade,
  kind varchar(14) not null,
  event_date date not null,
  detected boolean not null default false,
  review_status varchar(10) not null default 'pending',
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_life_event_kind_check check (kind in ('marriage', 'child', 'job_change',
    'salary_hike', 'new_loan', 'home_purchase', 'death', 'turned_18', 'job_loss', 'medical',
    'inheritance', 'other')),
  constraint fin_life_event_review_check check (review_status in ('pending', 'in_review', 'done'))
);
create index fin_life_event_user_idx on fin_life_event (user_id);

create table fin_review_run (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  kind varchar(10) not null,
  life_event_id uuid references fin_life_event(id) on delete set null,
  started_at timestamptz not null default now(),
  completed_at timestamptz,
  checklist jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_review_run_kind_check check (kind in ('monthly', 'annual', 'life_event'))
);
create index fin_review_run_user_idx on fin_review_run (user_id);
-- At most one monthly review per calendar month (the monthly job is idempotent).
create unique index fin_review_run_month on fin_review_run
  (user_id, date_trunc('month', started_at at time zone 'Asia/Kolkata')) where kind = 'monthly';
