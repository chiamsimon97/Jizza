-- Kai Portfolio: a connected source Kai pulls holdings from, such as a broker's MCP server added
-- on Connectors (Zerodha's Kite, say). Each source is tied to one demat or broker account. A sync
-- reads only the server's holdings tools and lands as an import batch the person reviews before
-- anything is saved, exactly as an uploaded holdings file does. Nothing here places an order.
create table fin_sync_source (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  server_id uuid not null references mcp_servers(id) on delete cascade,
  account_id uuid not null references fin_account(id) on delete cascade,
  enabled boolean not null default true,
  last_synced_at timestamptz,
  last_status varchar(16),
  last_batch_id uuid references fin_import_batch(id) on delete set null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_sync_source_status_check check (last_status is null or last_status in
    ('to_review', 'no_change', 'needs_sign_in', 'failed')),
  constraint fin_sync_source_once unique (user_id, server_id, account_id)
);
create index fin_sync_source_user_idx on fin_sync_source (user_id);
