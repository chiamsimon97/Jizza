-- Vita Health, Phase 1b part 1: the documents vault and reading a document into a draft (spec 4.2,
-- 5.3).
--
-- The file itself is a row of the shared files table, stored encrypted under the managing account,
-- the way chat attachments are. The text read from it and a locked PDF's password are encrypted
-- here (bytea, never returned in a row). The draft is what the model read, after Vita's own checks;
-- nothing reaches a record table until the user confirms it.

create table vita_document (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  source varchar(20) not null default 'manual',
  source_ref varchar(120),
  created_by_user_id uuid references users(id) on delete set null,
  deleted_at timestamptz,
  kind varchar(20) not null default 'other',
  title varchar(200),
  document_date date,
  doctor_id uuid references vita_doctor(id) on delete set null,
  condition_id uuid references vita_condition(id) on delete set null,
  file_id uuid references files(id) on delete set null,
  content_type varchar(80),
  size_bytes bigint,
  page_count integer,
  extracted_text_enc bytea,
  password_enc bytea,
  extraction_status varchar(8) not null default 'none',
  extraction_error varchar(300),
  extracted_at timestamptz,
  draft jsonb,
  confirmed_at timestamptz,
  confirmed_by_user_id uuid references users(id) on delete set null,
  private boolean not null default false,
  notes varchar(1000),
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_document_source_check check (source in
    ('manual', 'quick_start', 'upload', 'chat', 'mail', 'health_connect', 'apple_health')),
  constraint vita_document_kind_check check (kind in ('prescription', 'lab_report', 'scan',
    'discharge_summary', 'certificate', 'bill', 'policy', 'other')),
  constraint vita_document_extraction_check check (extraction_status in ('none', 'pending', 'done', 'failed')),
  constraint vita_document_size_check check (size_bytes is null or size_bytes >= 0)
);
create index vita_document_person_idx on vita_document (user_id, person_id, created_at);
create index vita_document_extracted_idx on vita_document (user_id, extracted_at) where extracted_at is not null;

-- Product limits for reading documents (spec 5.3), not clinical figures. A model call takes photos
-- up to 5 MB; uploads are capped at 10 MB.
insert into vita_config (key, scope, value) values
('extraction.limits', 'system', '{"pagesPerDocument": 30, "documentsPerDay": 20, "maxBytes": 10485760, "maxImageBytes": 5242880}'::jsonb),
-- The clock times a drafted schedule uses for "morning", "afternoon", "night" and "bedtime"; the
-- user's own reminder times when they set them.
('reminder.slot_times', 'system', '{"morning": "08:00", "afternoon": "14:00", "evening": "18:00", "night": "21:00", "bedtime": "22:00"}'::jsonb);

-- Prescription shorthand (spec 5.3): only ever used to DRAFT a schedule the user confirms. The
-- meanings are the standard Latin abbreviations; like every clinical key it shows "needs
-- verification" until someone checks it.
insert into vita_config (key, scope, value) values
('clinical.rx_shorthand', 'system', '{
  "codes": {
    "OD":  {"meaning": "once a day", "slots": ["morning"]},
    "BD":  {"meaning": "twice a day", "slots": ["morning", "night"]},
    "BID": {"meaning": "twice a day", "slots": ["morning", "night"]},
    "TDS": {"meaning": "three times a day", "slots": ["morning", "afternoon", "night"]},
    "TID": {"meaning": "three times a day", "slots": ["morning", "afternoon", "night"]},
    "HS":  {"meaning": "at bedtime", "slots": ["bedtime"]},
    "SOS": {"meaning": "only when needed", "asNeeded": true},
    "PRN": {"meaning": "only when needed", "asNeeded": true},
    "AC":  {"meaning": "before food", "withFood": "before"},
    "PC":  {"meaning": "after food", "withFood": "after"}
  },
  "pattern": {"meaning": "doses at morning-afternoon-night, as in 1-0-1", "slots": ["morning", "afternoon", "night"]}
}'::jsonb);
