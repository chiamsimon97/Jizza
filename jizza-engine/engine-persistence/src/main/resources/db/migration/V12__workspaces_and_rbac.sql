-- Accounts and roles. Every person has one personal workspace (their Individual account) and may
-- own or belong to organization workspaces. Who may do what inside a workspace is decided by the
-- role on the membership and the permission definitions in security/permissions.json, which is
-- deliberately not a table: a change to who can do what is a reviewed code change, not data.

create table workspaces (
  id uuid primary key default gen_random_uuid(),
  type varchar not null check (type in ('personal','organization')),
  name varchar(100) not null,
  created_by_user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);
-- Exactly one personal workspace per person.
create unique index workspaces_one_personal_per_user
  on workspaces (created_by_user_id) where type = 'personal';

create table workspace_members (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspaces(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  role varchar not null check (role in ('owner','admin','developer','user')),
  status varchar not null default 'active' check (status in ('active','suspended')),
  joined_at timestamptz not null default now(),
  version bigint not null default 0,
  unique (workspace_id, user_id)
);
-- A workspace has exactly one owner.
create unique index workspace_members_one_owner
  on workspace_members (workspace_id) where role = 'owner';
create index workspace_members_user_idx on workspace_members (user_id);

-- A membership request. Linked to the invitee's account when the email is registered; otherwise
-- it waits against the email and is linked when that person registers.
create table workspace_invitations (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references workspaces(id) on delete cascade,
  email citext not null,
  invitee_user_id uuid references users(id) on delete cascade,
  role varchar not null check (role in ('admin','developer','user')),
  status varchar not null default 'pending'
    check (status in ('pending','accepted','declined','revoked','expired')),
  -- SHA-256 of the sign-up link's token, only for emails with no account yet. Never the token.
  signup_token_hash text,
  invited_by_user_id uuid not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  responded_at timestamptz
);
create unique index workspace_invitations_one_pending
  on workspace_invitations (workspace_id, email) where status = 'pending';
create index workspace_invitations_invitee_idx
  on workspace_invitations (invitee_user_id) where status = 'pending';
create index workspace_invitations_email_idx
  on workspace_invitations (email) where status = 'pending';
create unique index workspace_invitations_token_idx
  on workspace_invitations (signup_token_hash) where signup_token_hash is not null;

-- Platform role: only ever set by configuration at startup, never through the API.
alter table users add column platform_role varchar not null default 'none'
  check (platform_role in ('none','super_admin'));
alter table users add column last_active_workspace_id uuid references workspaces(id) on delete set null;
-- The account a session is acting in. Switching issues a new session.
alter table sessions add column active_workspace_id uuid references workspaces(id) on delete set null;

-- Backfill: every existing person gets their personal workspace and owns it.
insert into workspaces (type, name, created_by_user_id)
select 'personal', 'Personal', u.id from users u;
insert into workspace_members (workspace_id, user_id, role)
select w.id, w.created_by_user_id, 'owner' from workspaces w where w.type = 'personal';
update users u set last_active_workspace_id = w.id
  from workspaces w where w.created_by_user_id = u.id and w.type = 'personal';

-- Connectors become workspace-owned. user_id stays as "created by".
alter table connections add column workspace_id uuid references workspaces(id) on delete cascade;
update connections c set workspace_id = w.id
  from workspaces w where w.created_by_user_id = c.user_id and w.type = 'personal';
alter table connections alter column workspace_id set not null;
create index connections_workspace_idx on connections (workspace_id);
do $$
declare idx text;
begin
  -- V4 created an unnamed unique index on (user_id, slug); slugs are now unique per workspace.
  select indexname into idx from pg_indexes
   where tablename = 'connections' and indexdef like '%UNIQUE%(user_id, slug)%';
  if idx is not null then execute 'drop index ' || quote_ident(idx); end if;
end $$;
create unique index connections_workspace_slug_idx on connections (workspace_id, slug);
