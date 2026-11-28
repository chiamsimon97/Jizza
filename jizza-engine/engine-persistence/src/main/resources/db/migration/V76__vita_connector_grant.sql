-- Vita Phase 3 (spec 4.2, 5.11): which health data a phone may send for a person. A grant is per
-- person, source (Health Connect or Apple Health) and phone; the phone's readings carry the
-- grant's id in vita_measurement.source_ref so revoking can remove what it wrote.
create table vita_connector_grant (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null,
  data_types text[] not null,
  device_id uuid references devices(id) on delete set null,
  device_name varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  granted_at timestamptz not null default now(),
  revoked_at timestamptz,
  last_sync_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_connector_grant_source_check check (source in ('health_connect', 'apple_health')),
  constraint vita_connector_grant_types_check check (cardinality(data_types) > 0 and data_types <@ array[
    'steps', 'sleep', 'workout', 'resting_heart_rate', 'bp', 'glucose', 'weight', 'spo2', 'temperature']::text[])
);
-- One live grant per person, source and phone.
create unique index vita_connector_grant_one_live on vita_connector_grant
  (user_id, person_id, source, coalesce(device_id, '00000000-0000-0000-0000-000000000000'::uuid))
  where revoked_at is null;
create index vita_measurement_source_ref_idx on vita_measurement (user_id, source_ref) where source_ref is not null;
