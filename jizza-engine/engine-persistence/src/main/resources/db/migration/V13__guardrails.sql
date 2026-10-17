-- Guardrails: the autonomy policy grows a fourth mode (let Jizza decide), finer action types,
-- rules scoped to one workflow or one step, locked ceilings, budgets, and a priority on what
-- reaches the user. Nothing here loosens what an existing account already had.

-- ── Rules ─────────────────────────────────────────────────────────────────────
alter table autonomy_policies drop constraint if exists autonomy_policies_rule_check;
alter table autonomy_policies
  add constraint autonomy_policies_rule_check check (rule in ('allow','ask','jizza','deny'));

-- One column per statement, so the schema check can read each of them.
alter table autonomy_policies add column workflow_id uuid references workflows(id) on delete cascade;
alter table autonomy_policies add column workflow_version int;
alter table autonomy_policies add column node_id varchar;
alter table autonomy_policies add column locked boolean not null default false;
-- An organization's ceiling: applies to every member's agents when they use that organization's
-- connectors, and is always locked.
alter table autonomy_policies
  add column workspace_id uuid references workspaces(id) on delete cascade;
alter table autonomy_policies add column created_at timestamptz not null default now();
alter table autonomy_policies add column updated_at timestamptz not null default now();

-- A step rule always belongs to a workflow, and a version only means something with one.
alter table autonomy_policies
  add constraint autonomy_policies_scope_check
  check ((node_id is null or workflow_id is not null)
     and (workflow_version is null or workflow_id is not null));

create index if not exists autonomy_policies_user on autonomy_policies (user_id);
create index autonomy_policies_workflow on autonomy_policies (workflow_id)
  where workflow_id is not null;
create index autonomy_policies_workspace on autonomy_policies (workspace_id)
  where workspace_id is not null;
alter table autonomy_policies
  add constraint autonomy_policies_workspace_check
  check (workspace_id is null or (locked and agent_id is null and workflow_id is null));

-- ── What each connector action does ───────────────────────────────────────────
alter table generated_tools
  add column action_type varchar
  check (action_type in ('read','draft','create','update','delete','send','money'));

update generated_tools set action_type = case
  when not side_effecting then 'read'
  when upper(http_method) in ('PUT','PATCH') then 'update'
  when upper(http_method) = 'DELETE' then 'delete'
  else 'create'
end;

-- ── Budgets: every call a budgeted rule let through, so the next one can be counted against it ──
create table guardrail_usage (
  id uuid primary key default gen_random_uuid(),
  policy_id uuid not null references autonomy_policies(id) on delete cascade,
  user_id uuid not null references users(id) on delete cascade,
  tool_ref varchar not null,
  amount numeric,
  created_at timestamptz not null default now()
);
create index guardrail_usage_policy_time on guardrail_usage (policy_id, created_at);

-- ── Priority and quiet hours ──────────────────────────────────────────────────
alter table notifications
  add column priority varchar not null default 'normal'
  check (priority in ('urgent','normal','low'));

create table guardrail_settings (
  user_id uuid primary key references users(id) on delete cascade,
  quiet_hours_start time,
  quiet_hours_end time,
  time_zone varchar,
  updated_at timestamptz not null default now()
);

-- ── Risk-based defaults for accounts that already exist ──────────────────────
-- Only accounts that were seeded get these, only where nothing already covers them, and only
-- rules that are stricter than what applied before.

-- Moving money is never done unattended by any agent that has not been allowed to.
insert into autonomy_policies (user_id, agent_id, tool_ref, rule, limits, precedence)
select u.id, null, '*:money', 'deny',
       '{"note":"No agent moves money unless you allow it"}'::jsonb, 0
from users u
where exists (select 1 from autonomy_policies p where p.user_id = u.id)
  and not exists (select 1 from autonomy_policies p
                  where p.user_id = u.id and p.agent_id is null and p.workflow_id is null
                    and p.tool_ref = '*:money');

-- Kai, the finance agent, asks before moving money and never deletes, unless the account had
-- already chosen to let Kai change things freely.
insert into autonomy_policies (user_id, agent_id, tool_ref, rule, limits, precedence)
select a.user_id, a.id, '*:money', 'ask',
       '{"note":"Money movement always asks"}'::jsonb, 10
from agents a
where a.key = 'finance'
  and exists (select 1 from autonomy_policies p where p.user_id = a.user_id)
  and not exists (select 1 from autonomy_policies p
                  where p.agent_id = a.id and p.workflow_id is null
                    and (p.tool_ref = '*:money'
                         or (p.rule = 'allow' and p.tool_ref in ('*', '*:side-effecting'))));

insert into autonomy_policies (user_id, agent_id, tool_ref, rule, limits, precedence)
select a.user_id, a.id, '*:delete', 'deny', null, 10
from agents a
where a.key = 'finance'
  and exists (select 1 from autonomy_policies p where p.user_id = a.user_id)
  and not exists (select 1 from autonomy_policies p
                  where p.agent_id = a.id and p.workflow_id is null
                    and (p.tool_ref = '*:delete'
                         or (p.rule = 'allow' and p.tool_ref in ('*', '*:side-effecting'))));
