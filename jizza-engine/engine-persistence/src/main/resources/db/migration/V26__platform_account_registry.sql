-- Jizza Public is the platform's back office, not an organization people join. Every Individual
-- and Team account is registered here when it is created, with where it came from, for the Super
-- Admin's analytics and approvals. People are members only when the Super Admin made them an
-- Admin or Developer.
create table platform_accounts (
  workspace_id uuid primary key references workspaces(id) on delete cascade,
  source varchar not null
    check (source in ('individual_signup','organization_signup','organization_created','existing')),
  registered_by_user_id uuid references users(id) on delete set null,
  registered_at timestamptz not null default now()
);
create index idx_platform_accounts_registered on platform_accounts (registered_at desc);

-- Accounts that existed before the registry.
insert into platform_accounts (workspace_id, source, registered_by_user_id, registered_at)
select id, 'existing', created_by_user_id, created_at from workspaces where not platform_public;

-- Undo the automatic User memberships: only people the Super Admin promoted stay.
delete from workspace_members m using workspaces w
 where w.id = m.workspace_id and w.platform_public and m.role = 'user';

-- It is not listed on Discover either.
update workspaces set listing_status = 'none', listed_at = null where platform_public;
update workspace_public_profiles p set approved_snapshot = null
  from workspaces w where w.id = p.workspace_id and w.platform_public;
