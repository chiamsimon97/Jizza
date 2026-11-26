-- Kai Portfolio: the goal check (does each goal meet its target?). The verdict compares the
-- simulated chance of reaching the target with the bar for the goal's priority
-- (priority.required_probability). Within this many points below the bar the goal is "at risk";
-- further below it is "off track". A planning choice, not a regulation. Results are kept in
-- fin_projection_run (method monte_carlo), so no new table is needed.
insert into fin_config (key, scope, value) values
('goals.verdict_margin', 'system', '{"points": 15}'::jsonb);
