-- The agents that maintain a workflow, in the order the person chose them. The first one's
-- guardrails judge each run; null or empty means the owning agent, else the person's Jizza.
alter table workflows add column maintainer_agent_ids jsonb;
