-- Vita Health, Phase 0: the foundation every later health phase builds on.
--
-- A person's health record hangs off the household people list Kai and Vita share (fin_person,
-- V50); nothing health-related is added to fin_person. Every row carries user_id, the account
-- that manages the record, and cascades from users, so Jizza's hard delete clears it. Person-owned
-- rows also cascade from fin_person. vita_config holds every Vita default and every clinical
-- figure, so none of them live in code. Nothing is reachable while jizza.health.enabled is off.
--
-- Clinical figures: a figure is seeded only where spec section 8 records a source, and every one
-- is seeded unverified (verified_on null), so the app shows "needs verification" until someone
-- checks it. A figure with no source has no row at all and reads as "not set: ask your doctor".
-- Section 8 names its sources but gives no links, so source_url holds the source as recorded;
-- replace it with the page's address when the figure is verified.

-- The date a person's status last changed ("Record closed on ..."). Not a health fact.
alter table fin_person add column status_on date;

-- Columns every person-owned Vita table shares (spec 4): id, user_id, person_id, source,
-- source_ref, created_by_user_id, deleted_at (soft delete), created_at, updated_at.

create table vita_profile (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  blood_group varchar(8),
  height_cm numeric(5, 1),
  sex_at_birth varchar(10),
  organ_donor boolean,
  preferred_hospital varchar(160),
  preferred_hospital_phone varchar(30),
  abha_number_enc bytea,
  abha_last4 varchar(4),
  timezone varchar(40) not null default 'Asia/Kolkata',
  allergies_none_known boolean not null default false,
  conditions_none_known boolean not null default false,
  reminder_mode varchar(12) not null default 'each_slot',
  notes text,
  -- Quick start (7.3): which steps were answered or skipped, so it can be left and resumed.
  quick_start jsonb not null default '{}'::jsonb,
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_profile_one_per_person unique (person_id),
  constraint vita_profile_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_profile_blood_group_check check (blood_group in
    ('a_pos', 'a_neg', 'b_pos', 'b_neg', 'ab_pos', 'ab_neg', 'o_pos', 'o_neg', 'unknown')),
  constraint vita_profile_sex_check check (sex_at_birth in ('female', 'male', 'intersex', 'unknown')),
  constraint vita_profile_reminder_mode_check check (reminder_mode in ('each_slot', 'daily_check', 'off')),
  constraint vita_profile_height_check check (height_cm is null or height_cm > 0)
);
create index vita_profile_user_idx on vita_profile (user_id);

create table vita_emergency_contact (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  name varchar(80) not null,
  relation varchar(40),
  phone varchar(30) not null,
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_emergency_contact_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health'))
);
create index vita_emergency_contact_person_idx on vita_emergency_contact (user_id, person_id);

-- Vita defaults and clinical figures, the same shape as fin_config. A system row has no user_id;
-- a user's row overrides it, and "Reset to Vita default" deletes the override. Keys under
-- clinical. are figures that need verification: until verified_on is set they carry a
-- "needs verification" badge. The emergency number is checked the same way (spec 8.2).
create table vita_config (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  key varchar(120) not null,
  scope varchar(10) not null,
  value jsonb not null,
  effective_from date not null default date '2000-01-01',
  effective_to date,
  source_url text,
  verified_on date,
  verified_by varchar(80),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_config_scope_check check (scope in ('system', 'user')),
  constraint vita_config_scope_owner check ((scope = 'system') = (user_id is null)),
  constraint vita_config_one_row unique nulls not distinct (key, user_id, effective_from)
);
create index vita_config_user_idx on vita_config (user_id);

-- One narrow table for every reading, in canonical units (mmHg, mg/dL, kg, degrees C).
create table vita_measurement (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(20) not null,
  value numeric(10, 3),
  value2 numeric(10, 3),
  value3 numeric(10, 3),
  unit varchar(12),
  context varchar(12),
  meal varchar(10),
  setting varchar(8),
  taken_at timestamptz,
  started_at timestamptz,
  ended_at timestamptz,
  detail jsonb,
  note varchar(500),
  pair_id uuid,
  device varchar(120),
  external_id varchar(200),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_measurement_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_measurement_kind_check check (kind in ('bp', 'glucose', 'weight', 'heart_rate', 'spo2',
    'temperature', 'steps', 'sleep', 'resting_heart_rate', 'workout')),
  constraint vita_measurement_context_check check (context in
    ('fasting', 'before_meal', 'after_meal', 'random', 'bedtime')),
  constraint vita_measurement_meal_check check (meal in ('breakfast', 'lunch', 'dinner')),
  constraint vita_measurement_setting_check check (setting in ('home', 'clinic'))
);
create index vita_measurement_person_idx on vita_measurement (user_id, person_id, kind, taken_at);
create unique index vita_measurement_external_once on vita_measurement (user_id, person_id, source, external_id)
  where external_id is not null;

create table vita_condition (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  name varchar(120) not null,
  template varchar(14) not null default 'generic',
  -- 'suspected' is only for what a doctor said; Vita never sets it.
  status varchar(10) not null default 'active',
  since date,
  -- vita_doctor arrives in Phase 1a; the reference is added there.
  doctor_id uuid,
  advice text,
  measures jsonb,
  private boolean not null default false,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_condition_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_condition_template_check check (template in ('diabetes_t2', 'hypertension', 'generic')),
  constraint vita_condition_status_check check (status in ('active', 'resolved', 'suspected'))
);
create index vita_condition_person_idx on vita_condition (user_id, person_id);

create table vita_allergy (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  substance varchar(120) not null,
  category varchar(12) not null default 'other',
  reaction varchar(300),
  severity varchar(10) not null default 'unknown',
  confirmed_by_doctor boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_allergy_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_allergy_category_check check (category in ('drug', 'food', 'environment', 'other')),
  constraint vita_allergy_severity_check check (severity in ('mild', 'moderate', 'severe', 'unknown'))
);
create index vita_allergy_person_idx on vita_allergy (user_id, person_id);

-- ── Seeds ───────────────────────────────────────────────────────────────────────────────────────

-- Display defaults (product choices, not clinical): values are always stored in metric units.
insert into vita_config (key, scope, value) values
('display.units', 'system', '{"weight": "kg", "height": "cm", "temperature": "C"}'::jsonb);

-- Red-flag rules (spec 8.2). Levels: urgent ("Act today") and emergency ("Call now"). A yes to the
-- rule's question raises urgent to emergency. The advice texts are clinical content from the named
-- source and are confirmed or replaced by the family's doctors before real use.
insert into vita_config (key, scope, value, source_url) values
('clinical.redflags.bp', 'system', '{
  "kind": "bp", "unit": "mmHg",
  "systolicAbove": 180, "diastolicAbove": 120,
  "level": "urgent", "raisedTo": "emergency",
  "question": "Any of these now?",
  "signs": ["chest pain", "shortness of breath", "back pain", "numbness", "weakness",
            "change in vision", "difficulty speaking"],
  "advice": "This reading is at a level where doctors advise urgent care. Wait one minute and measure again. If it is still this high and none of the signs are there, contact the doctor today.",
  "emergencyAdvice": "Call the emergency number now."
}'::jsonb,
 'American Heart Association, "Understanding blood pressure readings" (page read 27 Nov 2026)'),
('clinical.redflags.low_sugar', 'system', '{
  "kind": "glucose", "unit": "mg/dL",
  "below": 70, "veryLowBelow": 54,
  "level": "urgent", "raisedTo": "emergency",
  "question": "Does the person need someone else''s help to treat it?",
  "advice": "This reading is at a level where doctors advise acting today. Follow the plan the person''s doctor gave for a low reading, and call the doctor.",
  "emergencyAdvice": "Call the emergency number now."
}'::jsonb,
 'American Diabetes Association, hypoglycaemia levels 1 to 3 (search summary of the Standards of Care, not opened)'),
('clinical.redflags.self_harm', 'system', '{
  "helplineName": "Tele-MANAS", "helpline": "14416",
  "reply": "I''m really glad you told me. You don''t have to go through this alone. You can talk to someone right now at Tele-MANAS on 14416, any time of day, or call 112 if you are in danger. Nothing you said here is passed on to anyone unless you want it to be."
}'::jsonb,
 'Tele-MANAS 14416 (search summary of a Press Information Bureau release)');

-- Words in chat: no source is named yet (the doctor names and confirms it), so no source_url.
insert into vita_config (key, scope, value) values
('clinical.redflags.chat_words', 'system', '{
  "level": "emergency", "onlyWhenHappeningNow": true,
  "signs": ["chest pain", "face drooping", "arm weakness", "speech trouble",
            "severe breathing difficulty", "fainting"]
}'::jsonb);

-- Usual ranges and targets (spec 8.3): a general set and a set per condition template. A doctor's
-- target always overrides. "below x" excludes x; "atMost x" includes it; low-high includes both.
insert into vita_config (key, scope, value, source_url) values
('clinical.ranges.general.bp_clinic', 'system',
 '{"kind": "bp", "setting": "clinic", "unit": "mmHg", "systolic": {"below": 140}, "diastolic": {"below": 90}}'::jsonb,
 'India Hypertension Control Initiative protocol (search summary)'),
('clinical.ranges.general.bp_home', 'system',
 '{"kind": "bp", "setting": "home", "unit": "mmHg", "systolic": {"below": 135}, "diastolic": {"below": 85}}'::jsonb,
 'ISH 2020 guidelines (search summary)'),
('clinical.ranges.diabetes_t2.glucose_before_meal', 'system',
 '{"kind": "glucose", "context": "before_meal", "unit": "mg/dL", "low": 80, "high": 130, "lowInclusive": true, "highInclusive": true}'::jsonb,
 'ICMR Standard Treatment Workflow, type 2 diabetes, 2022 (search summary)'),
('clinical.ranges.diabetes_t2.glucose_after_meal', 'system',
 '{"kind": "glucose", "context": "after_meal", "unit": "mg/dL", "below": 180}'::jsonb,
 'ICMR Standard Treatment Workflow, type 2 diabetes, 2022 (search summary)'),
('clinical.ranges.diabetes_t2.hba1c', 'system',
 '{"test": "hba1c", "unit": "%", "atMost": 7.0, "note": "The source allows a higher target for older people; the doctor''s figure matters most."}'::jsonb,
 'ICMR Standard Treatment Workflow, type 2 diabetes, 2022 (search summary)');

-- High blood pressure uses the general BP set (spec 8.3: "As General"); no figure of its own.
insert into vita_config (key, scope, value) values
('clinical.ranges.hypertension.bp_clinic', 'system', '{"sameAs": "clinical.ranges.general.bp_clinic"}'::jsonb),
('clinical.ranges.hypertension.bp_home', 'system', '{"sameAs": "clinical.ranges.general.bp_home"}'::jsonb);

-- Check intervals (spec 8.4).
insert into vita_config (key, scope, value, source_url) values
('clinical.intervals.diabetes_t2.hba1c', 'system',
 '{"atTarget": {"timesPerYearAtLeast": 2}, "notAtTargetOrAfterChange": {"aboutEveryMonths": 3}}'::jsonb,
 'American Diabetes Association (search summary)'),
('clinical.intervals.diabetes_t2.eye', 'system', '{"timesPerYearAtLeast": 1}'::jsonb,
 'American Diabetes Association (search summary)'),
('clinical.intervals.diabetes_t2.foot', 'system', '{"timesPerYearAtLeast": 1}'::jsonb,
 'American Diabetes Association (search summary)'),
('clinical.intervals.diabetes_t2.urine_albumin', 'system', '{"timesPerYearAtLeast": 1}'::jsonb,
 'American Diabetes Association (search summary)');

-- Emergency numbers (spec 8.2): 112 by default, plus the ambulance number where the person lives,
-- which only the user can fill in.
insert into vita_config (key, scope, value, source_url) values
('emergency.numbers', 'system', '{"emergency": "112", "ambulanceWhereTheyLive": ""}'::jsonb,
 'Ministry of Home Affairs: 112 is the single emergency number (search summary)');

-- Vita's area names the health record too, so a plain-chat message with a reading, a medicine or a
-- blood group is routed to Vita rather than answered by Jizza. Only the product's own wording is
-- replaced; a description the user changed stays as it is.
update agents
   set description = 'Health: health records, readings, medicines, lab reports, doctors and visits, allergies, fitness, sleep, nutrition, symptoms.'
 where key = 'health'
   and not is_orchestrator
   and description = 'Health: fitness, sleep, nutrition, symptoms, appointments, medications.';
