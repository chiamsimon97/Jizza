-- The Family plan (spec 4.4) and Vita Health sharing (spec 4.2, 5.10), Phase 2a.

-- A Family is its own plan: an account with members, and none of the organization-only features.
-- Forward only: the old check is replaced by one that also allows 'family'.
alter table workspaces drop constraint if exists workspaces_type_check;
alter table workspaces add constraint workspaces_type_check check (type in ('personal', 'organization', 'family'));

alter table platform_accounts drop constraint if exists platform_accounts_source_check;
alter table platform_accounts add constraint platform_accounts_source_check
  check (source in ('individual_signup', 'organization_signup', 'organization_created', 'family_created', 'existing'));

-- A consent note is for money or for health. Every note recorded before this was for money.
alter table fin_consent add column domain varchar(8) not null default 'finance';
alter table fin_consent add constraint fin_consent_domain_check check (domain in ('finance', 'health'));

-- One section of one person's record shared by its manager with one Family member. A row is never
-- deleted when the share ends: ended_at and ended_reason say when and why, for the access list.
create table vita_share (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  grantee_user_id uuid not null references users(id) on delete cascade,
  as_self boolean not null default false,
  section varchar(14) not null,
  access varchar(4) not null,
  granted_at timestamptz not null default now(),
  ended_at timestamptz,
  ended_reason varchar(16),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint vita_share_section_check check (section in ('profile', 'emergency', 'conditions', 'allergies',
    'medicines', 'vitals', 'labs', 'documents', 'visits', 'vaccinations', 'events', 'insurance', 'journal', 'goals')),
  constraint vita_share_access_check check (access in ('view', 'edit')),
  constraint vita_share_not_self check (grantee_user_id <> user_id),
  constraint vita_share_ended_check check ((ended_at is null) = (ended_reason is null)),
  constraint vita_share_reason_check check (ended_reason is null or ended_reason in
    ('revoked', 'left_family', 'removed', 'account_deleted', 'record_closed', 'replaced'))
);
create unique index vita_share_one_active on vita_share (person_id, grantee_user_id, section) where ended_at is null;
create index vita_share_grantee_idx on vita_share (grantee_user_id) where ended_at is null;
create index vita_share_user_idx on vita_share (user_id, person_id);

-- At most one Family member is linked to a record as the person themself (checked again in code
-- across sections).
create unique index vita_share_one_self on vita_share (person_id, section) where as_self and ended_at is null;

-- Notes between the manager and helpers about one person; they follow the visits section.
create table vita_care_note (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid not null references fin_person(id) on delete cascade,
  text varchar(2000) not null,
  written_by_user_id uuid references users(id) on delete set null,
  written_at timestamptz not null default now(),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index vita_care_note_person_idx on vita_care_note (user_id, person_id, written_at);

-- A member linked to their own record opens on "My medicines today" (spec 7.20) unless they turn
-- it off. A product setting, not a clinical figure.
insert into vita_config (key, scope, value) values
('ui.open_my_medicines', 'system', '{"enabled": true}'::jsonb);
