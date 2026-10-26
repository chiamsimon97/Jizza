-- Public functions: ready-made functions every person sees under Functions and calls as
-- fn.<name>(…), but nobody changes. They have no user. Jizza saves them at startup from
-- functions/public-functions.json (engine-workflow); a person's own function can't take a public
-- function's name.
alter table script_functions alter column user_id drop not null;

create unique index script_functions_public_name on script_functions (name) where user_id is null;
