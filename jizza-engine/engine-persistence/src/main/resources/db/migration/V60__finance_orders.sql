-- Kai Portfolio: orders the person places from Kai (part D). The person prepares each order,
-- checks it and confirms it with their password; Kai never places one on its own, and nothing
-- here schedules or repeats an order. Each row records what was sent and the broker's reply. The
-- tax lots change only when the next holdings sync shows the trade, never from this table.
create table fin_order (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  account_id uuid not null references fin_account(id) on delete cascade,
  instrument_id uuid not null references fin_instrument(id) on delete cascade,
  server_id uuid references mcp_servers(id) on delete set null,
  plan_id uuid references fin_plan(id) on delete set null,
  side varchar(4) not null,
  quantity numeric(18,6) not null,
  order_type varchar(6) not null,
  limit_price numeric(18,4),
  status varchar(10) not null,
  broker_order_id varchar(64),
  broker_reply varchar(500),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_order_side_check check (side in ('buy', 'sell')),
  constraint fin_order_type_check check (order_type in ('market', 'limit')),
  constraint fin_order_status_check check (status in ('sending', 'sent', 'rejected')),
  constraint fin_order_qty_check check (quantity > 0)
);
create index fin_order_user_idx on fin_order (user_id, created_at desc);
