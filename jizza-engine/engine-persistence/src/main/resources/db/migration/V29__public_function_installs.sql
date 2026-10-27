-- Public functions are installed before they are used: a person picks them under Explore public
-- functions, and only the installed ones are listed under Functions, offered to Script steps and
-- reachable as fn.<name>(…).
create table script_function_installs (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id) on delete cascade,
  function_id uuid not null references script_functions(id) on delete cascade,
  installed_at timestamptz not null default now(),
  unique (user_id, function_id)
);
