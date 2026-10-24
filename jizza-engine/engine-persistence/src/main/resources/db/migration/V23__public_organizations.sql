-- Public organizations. An organization stays private until its Owner or Admin asks for a public
-- listing and a Super Admin approves it. Only the approved snapshot is ever shown to other people,
-- so an edit to a listed profile waits for review while the approved version stays visible.
alter table workspaces add column listing_status varchar not null default 'none'
  check (listing_status in ('none','pending','approved','rejected','suspended'));
alter table workspaces add column listed_at timestamptz;

create index idx_workspaces_listing_status on workspaces (listing_status)
  where listing_status <> 'none';

-- The profile the owner edits, and approved_snapshot, the version other users see (null while the
-- organization has never been approved, or after it was unlisted).
create table workspace_public_profiles (
  workspace_id uuid primary key references workspaces(id) on delete cascade,
  display_name varchar(100) not null,
  description varchar(500) not null default '',
  category varchar(40) not null default 'other',
  logo_url varchar(500),
  website varchar(500),
  approved_snapshot jsonb,
  submitted_at timestamptz,
  updated_at timestamptz not null default now()
);

-- Append-only: every submit, decision and unlist, with who did it and why.
create table workspace_listing_reviews (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspaces(id) on delete cascade,
  action varchar not null
    check (action in ('submitted','approved','rejected','withdrawn','unlisted','suspended','reinstated')),
  actor_user_id uuid references users(id) on delete set null,
  note varchar(1000),
  created_at timestamptz not null default now()
);

create index idx_listing_reviews_workspace on workspace_listing_reviews (workspace_id, created_at desc);

-- Asking to join a listed organization. An Owner or Admin accepts (the person joins as a User) or
-- declines. One pending request per person per organization.
create table workspace_join_requests (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspaces(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  message varchar(500),
  status varchar not null default 'pending'
    check (status in ('pending','accepted','declined','cancelled')),
  decided_by_user_id uuid references users(id) on delete set null,
  created_at timestamptz not null default now(),
  decided_at timestamptz
);

create unique index workspace_join_requests_one_pending
  on workspace_join_requests (workspace_id, user_id) where status = 'pending';
create index idx_join_requests_workspace on workspace_join_requests (workspace_id, created_at desc);
