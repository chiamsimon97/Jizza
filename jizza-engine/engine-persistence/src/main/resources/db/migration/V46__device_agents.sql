-- Which agents may use a device's actions, by agent key ("*" for every agent). Null keeps the
-- earlier rule: only Jizza, the person's chat agent.
alter table devices add column agent_keys jsonb;
