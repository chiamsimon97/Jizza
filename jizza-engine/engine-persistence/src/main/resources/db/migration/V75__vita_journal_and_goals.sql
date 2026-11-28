-- Vita Health, Phase 2c: the daily journal and health goals (spec 4.2, 5.7). No cycle tracking.

-- One entry per person per day. Private unless the person's journal section is shared.
create table vita_journal_entry (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  entry_date date not null,
  mood smallint,
  energy smallint,
  pain smallint,
  symptoms text[],
  notes varchar(2000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_journal_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_journal_mood_check check (mood is null or mood between 1 and 5),
  constraint vita_journal_energy_check check (energy is null or energy between 1 and 5),
  constraint vita_journal_pain_check check (pain is null or pain between 0 and 10)
);
create unique index vita_journal_one_per_day on vita_journal_entry (person_id, entry_date) where deleted_at is null;
create index vita_journal_person_idx on vita_journal_entry (user_id, person_id, entry_date);

-- A goal. One for a medical measure (bp_range, glucose_range, hba1c) stores no target of its own:
-- it reads the doctor's target or the configured Vita default at the time it is shown. The weekly
-- plan is what the user typed; Vita proposes no figure.
create table vita_goal (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(20) not null,
  title varchar(120),
  context varchar(20),
  target numeric(12,3),
  target2 numeric(12,3),
  -- A weight goal's starting weight, which says whether it is to go down or up.
  start_value numeric(12,3),
  unit varchar(20),
  by_date date,
  weekly_plan jsonb,
  status varchar(10) not null default 'active',
  met_on date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_goal_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_goal_kind_check check (kind in
    ('weight', 'steps', 'sleep_hours', 'bp_range', 'glucose_range', 'hba1c', 'water', 'custom')),
  constraint vita_goal_status_check check (status in ('active', 'met', 'paused', 'dropped')),
  constraint vita_goal_context_check check (context is null or context in ('before_meal', 'after_meal')),
  -- A medical goal never keeps a figure of its own.
  constraint vita_goal_medical_no_target check (kind not in ('bp_range', 'glucose_range', 'hba1c')
    or (target is null and target2 is null))
);
create index vita_goal_person_idx on vita_goal (user_id, person_id, status);

-- A week's value for a goal, typed by the user. Weeks with none are filled from measurements when
-- shown, never stored as typed.
create table vita_goal_checkin (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  goal_id uuid not null references vita_goal(id) on delete cascade,
  created_by_user_id uuid references users(id) on delete set null,
  week_start date not null,
  value numeric(12,3),
  value2 numeric(12,3),
  note varchar(500),
  source varchar(20) not null default 'manual',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_goal_checkin_one unique (goal_id, week_start),
  constraint vita_goal_checkin_monday check (extract(isodow from week_start) = 1),
  constraint vita_goal_checkin_source_check check (source in ('manual', 'measurements'))
);
create index vita_goal_checkin_person_idx on vita_goal_checkin (user_id, person_id);

-- How many weeks in a row a weekly value must meet the target before a goal is met (spec 5.7):
-- a product choice the user can change.
insert into vita_config (key, scope, value) values
('goals.met_after_weeks', 'system', '{"weeks": 4}'::jsonb);
