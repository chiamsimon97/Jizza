-- Vita Health, Phase 1b part 3: vaccinations, and surgeries and stays (spec 4.2). Visit prep and the
-- doctor-ready summary read existing rows and add no table.

-- A vaccine dose given. next_due_on is typed by the user (from the card or the doctor); Vita works
-- out no schedule of its own.
create table vita_vaccination (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  vaccine varchar(120) not null,
  dose_number integer,
  given_on date,
  place varchar(200),
  batch varchar(60),
  document_id uuid references vita_document(id) on delete set null,
  next_due_on date,
  notes varchar(1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_vaccination_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_vaccination_dose_check check (dose_number is null or dose_number between 1 and 20),
  constraint vita_vaccination_has_date check (given_on is not null or next_due_on is not null)
);
create index vita_vaccination_person_idx on vita_vaccination (user_id, person_id, given_on);

-- Surgeries, procedures, hospital stays, injuries and falls.
create table vita_event (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(10) not null,
  title varchar(200),
  from_date date not null,
  to_date date,
  hospital varchar(200),
  doctor_id uuid references vita_doctor(id) on delete set null,
  notes varchar(4000),
  document_ids uuid[],
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_event_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_event_kind_check check (kind in ('surgery', 'procedure', 'admission', 'injury', 'fall')),
  constraint vita_event_dates_check check (to_date is null or to_date >= from_date)
);
create index vita_event_person_idx on vita_event (user_id, person_id, from_date);
