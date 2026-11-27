-- Vita Health, Phase 1a part 2: medicines, their schedules and every change, the dose log and
-- other reminders (spec 4.2, 5.2).
--
-- Vita keeps the list and reminds. A medicine name is free text exactly as the user gave it; there
-- is no drug database and nothing is completed. Schedules are in the person's own zone
-- (vita_profile.timezone); a change closes the schedule and opens a new one, so the history keeps
-- both. Dose rows are made for the next 48 hours and are unique per schedule and time, so making
-- them twice adds nothing. Dose reminders come from vita_dose_log; vita_reminder holds the rest.

create table vita_medicine (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  name varchar(120) not null,
  brand_name varchar(120),
  generic_name varchar(120),
  strength varchar(40),
  form varchar(12) not null default 'tablet',
  system varchar(12) not null default 'allopathy',
  condition_id uuid references vita_condition(id) on delete set null,
  prescribed_by uuid references vita_doctor(id) on delete set null,
  start_date date not null default current_date,
  end_date date,
  as_needed boolean not null default false,
  track_stock boolean not null default false,
  stock_units numeric(10, 2),
  units_per_pack numeric(10, 2),
  refill_lead_days integer,
  -- When the one refill reminder for the current stock went out; cleared by a refill.
  refill_reminded_at timestamptz,
  status varchar(8) not null default 'active',
  private boolean not null default false,
  document_id uuid,
  notes varchar(1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_medicine_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_medicine_form_check check (form in ('tablet', 'capsule', 'syrup', 'injection', 'inhaler',
    'drops', 'cream', 'supply', 'other')),
  constraint vita_medicine_system_check check (system in ('allopathy', 'ayurveda', 'homeopathy', 'siddha',
    'unani', 'supplement', 'other')),
  constraint vita_medicine_status_check check (status in ('active', 'paused', 'stopped')),
  constraint vita_medicine_stock_check check (stock_units is null or stock_units >= 0),
  constraint vita_medicine_dates_check check (end_date is null or end_date >= start_date)
);
create index vita_medicine_person_idx on vita_medicine (user_id, person_id);

create table vita_medicine_schedule (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  medicine_id uuid not null references vita_medicine(id) on delete cascade,
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  -- Local times in the person's zone, as "HH:mm".
  times text[] not null,
  recurrence varchar(200) not null default 'FREQ=DAILY',
  dose_units numeric(8, 2) not null default 1,
  dose_unit varchar(8) not null default 'tablet',
  with_food varchar(8) not null default 'any',
  effective_from date not null,
  effective_to date,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_medicine_schedule_unit_check check (dose_unit in ('tablet', 'ml', 'units', 'puffs', 'drops', 'other')),
  constraint vita_medicine_schedule_food_check check (with_food in ('before', 'with', 'after', 'any')),
  constraint vita_medicine_schedule_dates_check check (effective_to is null or effective_to >= effective_from - 1),
  constraint vita_medicine_schedule_has_time check (cardinality(times) between 1 and 12)
);
create index vita_medicine_schedule_medicine_idx on vita_medicine_schedule (medicine_id);
create index vita_medicine_schedule_open_idx on vita_medicine_schedule (user_id) where effective_to is null;

create table vita_medicine_event (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  medicine_id uuid not null references vita_medicine(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(12) not null,
  on_date date not null,
  from_text varchar(200),
  to_text varchar(200),
  reason varchar(500),
  doctor_id uuid references vita_doctor(id) on delete set null,
  visit_id uuid references vita_visit(id) on delete set null,
  document_id uuid,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_medicine_event_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_medicine_event_kind_check check (kind in ('started', 'dose_changed', 'paused', 'resumed',
    'stopped', 'refilled'))
);
create index vita_medicine_event_medicine_idx on vita_medicine_event (medicine_id, on_date);
create index vita_medicine_event_person_idx on vita_medicine_event (user_id, person_id, on_date);

create table vita_dose_log (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  medicine_id uuid not null references vita_medicine(id) on delete cascade,
  schedule_id uuid not null references vita_medicine_schedule(id) on delete cascade,
  deleted_at timestamptz,
  due_at timestamptz not null,
  status varchar(8) not null default 'due',
  snoozed_until timestamptz,
  taken_at timestamptz,
  dose_taken numeric(8, 2),
  acted_at timestamptz,
  acted_by_user_id uuid references users(id) on delete set null,
  note varchar(500),
  -- When the slot reminder and the missed alert for this row went out (one each, per slot).
  reminded_at timestamptz,
  missed_alerted_at timestamptz,
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_dose_log_status_check check (status in ('due', 'taken', 'skipped', 'missed')),
  constraint vita_dose_log_once unique (schedule_id, due_at)
);
create index vita_dose_log_due_idx on vita_dose_log (status, due_at);
create index vita_dose_log_person_idx on vita_dose_log (user_id, person_id, due_at);

create table vita_reminder (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(16) not null,
  ref_id uuid,
  title varchar(200),
  due_at timestamptz not null,
  recurrence varchar(200),
  status varchar(10) not null default 'scheduled',
  sent_at timestamptz,
  notify_user_ids uuid[],
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_reminder_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_reminder_kind_check check (kind in ('refill', 'visit', 'vaccination', 'policy_renewal',
    'check_due', 'daily_check', 'custom')),
  constraint vita_reminder_status_check check (status in ('scheduled', 'sent', 'done', 'dismissed', 'cancelled'))
);
create index vita_reminder_due_idx on vita_reminder (status, due_at);
create index vita_reminder_person_idx on vita_reminder (user_id, person_id);

-- Reminder settings (spec 5.2): product choices, not clinical figures.
insert into vita_config (key, scope, value) values
('reminder.missed_after_minutes', 'system', '{"minutes": 60}'::jsonb),
('reminder.snooze_minutes', 'system', '{"minutes": 15}'::jsonb),
('reminder.daily_check_time', 'system', '{"time": "20:00"}'::jsonb),
('reminder.show_medicine_names', 'system', '{"enabled": false}'::jsonb),
('reminder.lead_days', 'system', '{"visit": 1, "vaccination": 7, "policy_renewal": 30, "check_due": 14}'::jsonb);
