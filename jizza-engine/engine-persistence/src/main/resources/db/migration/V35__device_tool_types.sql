-- What the owner says each tool a device added itself does, as {"x-acme.export": "READ"}. The
-- guardrails judge such a tool as moving money until the owner says otherwise, because the server
-- cannot check what the app claims.
alter table devices add column tool_types jsonb not null default '{}';
