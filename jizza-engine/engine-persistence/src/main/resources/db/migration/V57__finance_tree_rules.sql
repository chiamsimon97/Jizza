-- Kai Portfolio, Phase 2 (3 of 3): each goal's asset tree, the rules on it, and the lists.
--
-- A goal's tree is asset class → sub-category → instrument, each node with its share of its
-- parent; siblings with a share add up to 100 (checked by the service). An instrument node with
-- no share is a holding the user chose to keep there: user_choice_ack_at records when they
-- confirmed it (a short goal holding shares, say) so Kai shows "Your choice" and stops asking.
--
-- A rule is one field at one level: instrument in goal, sub-category, asset class, the goal, or
-- the user's own defaults. Each field is inherit, set or off; a level can lock it so the levels
-- below cannot loosen it. Nothing here places an order.

create table fin_node (
  id uuid primary key default gen_random_uuid(),
  user_id uuid references users(id) on delete cascade,
  workspace_id uuid references workspaces(id) on delete cascade,
  plan_id uuid not null references fin_plan(id) on delete cascade,
  parent_id uuid references fin_node(id) on delete cascade,
  kind varchar(14) not null,
  node_key varchar(40) not null,
  name varchar(120) not null,
  instrument_id uuid references fin_instrument(id) on delete cascade,
  target_pct numeric(7,4),
  in_rebalance_pool boolean not null default true,
  user_choice_ack_at timestamptz,
  user_choice_note varchar(200),
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_node_owner_check check ((user_id is null) <> (workspace_id is null)),
  constraint fin_node_kind_check check (kind in ('asset_class', 'sub_category', 'instrument')),
  constraint fin_node_instrument_check check ((kind = 'instrument') = (instrument_id is not null)),
  constraint fin_node_pct_check check (target_pct is null or target_pct between 0 and 100),
  constraint fin_node_pct_needed check (kind = 'instrument' or target_pct is not null)
);
create index fin_node_user_idx on fin_node (user_id);
create index fin_node_plan_idx on fin_node (plan_id);
create unique index fin_node_key on fin_node (plan_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), node_key);

create table fin_rule (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  scope_level varchar(12) not null,
  plan_id uuid references fin_plan(id) on delete cascade,
  node_id uuid references fin_node(id) on delete cascade,
  instrument_id uuid references fin_instrument(id) on delete cascade,
  rule_type varchar(18) not null,
  field varchar(40) not null,
  state varchar(7) not null,
  locked boolean not null default false,
  is_hard_constraint boolean not null default false,
  params jsonb not null default '{}'::jsonb,
  condition_jexl text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_rule_level_check
    check (scope_level in ('user', 'goal', 'asset_class', 'sub_category', 'instrument')),
  constraint fin_rule_type_check check (rule_type in ('stop_loss', 'profit_booking', 'exit',
    'rebalance_band', 'accumulate', 'concentration_cap', 'execution_mode')),
  constraint fin_rule_state_check check (state in ('inherit', 'set', 'off')),
  constraint fin_rule_scope_check check (
    (scope_level = 'user' and plan_id is null and node_id is null and instrument_id is null)
    or (scope_level = 'goal' and plan_id is not null and node_id is null and instrument_id is null)
    or (scope_level in ('asset_class', 'sub_category') and plan_id is not null and node_id is not null)
    or (scope_level = 'instrument' and plan_id is not null and instrument_id is not null))
);
create index fin_rule_user_idx on fin_rule (user_id);
create unique index fin_rule_one on fin_rule (user_id, scope_level,
  coalesce(plan_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(node_id, '00000000-0000-0000-0000-000000000000'::uuid),
  coalesce(instrument_id, '00000000-0000-0000-0000-000000000000'::uuid), rule_type, field);

create table fin_rule_template (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  name varchar(80) not null,
  rules jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_rule_template_one unique (user_id, name)
);

create table fin_list_item (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  person_id uuid references fin_person(id) on delete cascade,
  list_type varchar(5) not null,
  instrument_id uuid not null references fin_instrument(id) on delete cascade,
  plan_id uuid references fin_plan(id) on delete set null,
  node_id uuid references fin_node(id) on delete set null,
  notes varchar(500),
  priority int not null default 0,
  target_entry numeric(18,4),
  planned_amount numeric(18,2),
  conviction int,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint fin_list_item_type_check check (list_type in ('watch', 'next')),
  constraint fin_list_item_conviction_check check (conviction is null or conviction between 1 and 5)
);
create index fin_list_item_user_idx on fin_list_item (user_id);

-- Kai's rule defaults: the starting point of "Your defaults". Planning choices, not regulation.
-- The 5/25 band: a class with a target of 20% or more rebalances on a 5-point move, a smaller one
-- on a 25% relative move. Observe until Phase 4; stop loss and profit booking start off.
delete from fin_config where scope = 'system' and key = 'rules.defaults';
insert into fin_config (key, scope, value) values
('rules.defaults', 'system', '{
  "rebalance_band": {"state": "set", "params": {"mode": "five_twenty_five", "absolutePts": 5, "relativePct": 25, "largeFromPct": 20}},
  "execution_mode": {"state": "set", "params": {"mode": "observe"}},
  "stop_loss": {"state": "off", "params": {}},
  "profit_booking": {"state": "off", "params": {}},
  "exit": {"state": "off", "params": {}},
  "accumulate": {"state": "off", "params": {}},
  "concentration_cap": {"state": "off", "params": {}}
}'::jsonb);
