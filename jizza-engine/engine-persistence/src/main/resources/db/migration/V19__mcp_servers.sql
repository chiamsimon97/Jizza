-- Remote MCP servers added from the app (custom connectors, as in Claude). Secrets and tokens are
-- envelope-encrypted like a connection's; the tool list is what the server last said it offers,
-- and tool_settings is what the user decided about each tool (on or off, and what it does).
create table mcp_servers (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  workspace_id uuid not null references workspaces(id) on delete cascade,
  name varchar not null,
  slug varchar not null,
  url text not null,
  auth_type varchar not null default 'none' check (auth_type in ('none','bearer','header','oauth')),
  header_name varchar,
  secret bytea,
  enabled boolean not null default true,
  agent_keys jsonb not null default '["jizza"]',
  redact_arguments boolean not null default false,
  tools jsonb,
  tool_settings jsonb not null default '{}',
  tools_refreshed_at timestamptz,
  status varchar not null default 'new' check (status in ('new','connected','needs_auth','error')),
  last_error text,
  server_name varchar,
  server_version varchar,
  oauth_client_id text,
  oauth_client_secret bytea,
  oauth_authorization_url text,
  oauth_token_url text,
  oauth_scopes text,
  oauth_resource text,
  access_token bytea,
  refresh_token bytea,
  token_expires_at timestamptz,
  oauth_state varchar,
  oauth_verifier bytea,
  oauth_session_id uuid,
  oauth_state_expires_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- The slug names every tool an agent sees, so one person cannot have two servers share it.
  unique (user_id, slug)
);

create index mcp_servers_workspace_idx on mcp_servers (workspace_id);
create unique index mcp_servers_oauth_state_idx on mcp_servers (oauth_state) where oauth_state is not null;
