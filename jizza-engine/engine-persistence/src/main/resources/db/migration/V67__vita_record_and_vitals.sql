-- Vita Health, Phase 1a part 1: doctors, visits and the person's own ranges and doctors' targets.
--
-- Doctors are household-wide (user_id, no person_id): one doctor can see several people. A visit
-- belongs to one person and may name a doctor. vita_usual_range holds a range the user typed
-- ("own") or a target a doctor set ("doctor", with the doctor and the date). A missing row means
-- the Vita default from vita_config, or "not set" when config has no figure. No clinical number is
-- added here.

create table vita_doctor (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(10) not null default 'doctor',
  name varchar(120) not null,
  speciality varchar(80),
  clinic varchar(120),
  phone varchar(30),
  address varchar(300),
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_doctor_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_doctor_kind_check check (kind in ('doctor', 'hospital', 'clinic', 'lab', 'pharmacy'))
);
create index vita_doctor_user_idx on vita_doctor (user_id);

-- A condition may name the doctor who looks after it (the column exists since V66).
alter table vita_condition
  add constraint vita_condition_doctor_fk foreign key (doctor_id) references vita_doctor(id) on delete set null;

create table vita_visit (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  doctor_id uuid references vita_doctor(id) on delete set null,
  scheduled_at timestamptz,
  status varchar(10) not null default 'planned',
  reason varchar(300),
  -- Questions to ask and the prep arrive in Phase 1b.
  questions jsonb,
  notes text,
  follow_up_on date,
  document_ids uuid[],
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_visit_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_visit_status_check check (status in ('planned', 'done', 'cancelled'))
);
create index vita_visit_person_idx on vita_visit (user_id, person_id, scheduled_at);
create index vita_visit_doctor_idx on vita_visit (doctor_id);

-- The person's own range or a doctor's target. For blood pressure low/high bound the systolic and
-- low2/high2 the diastolic. A bound is inclusive or not, as written ("below 140" is not).
create table vita_usual_range (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(20),
  lab_test_code varchar(40),
  context varchar(12),
  setting varchar(8),
  low numeric(10, 3),
  high numeric(10, 3),
  low2 numeric(10, 3),
  high2 numeric(10, 3),
  low_inclusive boolean not null default true,
  high_inclusive boolean not null default true,
  set_by varchar(8) not null default 'user',
  doctor_id uuid references vita_doctor(id) on delete set null,
  set_on date,
  note varchar(500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_usual_range_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_usual_range_what check ((kind is null) <> (lab_test_code is null)),
  constraint vita_usual_range_kind_check check (kind in ('bp', 'glucose', 'weight', 'heart_rate', 'spo2',
    'temperature', 'resting_heart_rate')),
  constraint vita_usual_range_context_check check (context in
    ('fasting', 'before_meal', 'after_meal', 'random', 'bedtime')),
  constraint vita_usual_range_setting_check check (setting in ('home', 'clinic')),
  constraint vita_usual_range_set_by_check check (set_by in ('user', 'doctor')),
  constraint vita_usual_range_has_bound check (low is not null or high is not null
    or low2 is not null or high2 is not null),
  constraint vita_usual_range_doctor_named check (set_by = 'user' or doctor_id is not null)
);
-- One live range per person, measure, context and setting.
create unique index vita_usual_range_one_live on vita_usual_range
  (user_id, person_id, coalesce(kind, ''), coalesce(lab_test_code, ''), coalesce(context, ''), coalesce(setting, ''))
  where deleted_at is null;

-- Readings (spec 4.2): the duplicate check and the second reading look these up.
create index vita_measurement_pair_idx on vita_measurement (pair_id) where pair_id is not null;
create index vita_measurement_recent_idx on vita_measurement (user_id, person_id, taken_at desc)
  where deleted_at is null;

-- Product choices, not clinical figures.
insert into vita_config (key, scope, value) values
('entry.duplicate_window_minutes', 'system', '{"minutes": 10}'::jsonb),
('entry.red_flag_recent_hours', 'system', '{"hours": 24}'::jsonb);

-- Two seed corrections from the verification sheet (plan step 2), each matching the seed to its
-- own cited source. Both stay unverified (verified_on null) until Chiam checks them.
-- 1. The AHA page (heart.org/bplevels, read 27 Nov 2026) says wait five minutes, not one.
update vita_config
   set value = jsonb_set(value, '{advice}', to_jsonb(replace(value->>'advice', 'Wait one minute', 'Wait five minutes'))),
       source_url = 'https://heart.org/bplevels (American Heart Association, "Understanding blood pressure readings", read 27 Nov 2026)'
 where key = 'clinical.redflags.bp' and user_id is null;
-- 2. The PIB release of 29 May 2024 lists 1-800-891-4416 beside 14416.
update vita_config
   set value = value
         || '{"helplineAlt": "1-800-891-4416"}'::jsonb
         || jsonb_build_object('reply', replace(value->>'reply', 'on 14416,', 'on 14416 or 1-800-891-4416,')),
       source_url = 'https://pib.gov.in/PressReleasePage.aspx?PRID=2022057 (Press Information Bureau, 29 May 2024)'
 where key = 'clinical.redflags.self_harm' and user_id is null;
