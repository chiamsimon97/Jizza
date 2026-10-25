-- The platform's own Public organization. Jizza creates it at startup, owned by the Super Admin and
-- listed on Discover, and refuses to start without it outside development. At most one exists.
alter table workspaces add column platform_public boolean not null default false;
alter table workspaces add constraint workspaces_platform_public_is_org
  check (not platform_public or type = 'organization');
create unique index workspaces_one_platform_public on workspaces ((true)) where platform_public;
