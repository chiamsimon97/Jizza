-- K7: older MCP servers (SSE) and servers that run on a paired desktop app.
alter table mcp_servers add column transport varchar not null default 'streamable_http';
alter table mcp_servers add column device_id uuid references devices(id) on delete cascade;
alter table mcp_servers add column device_server varchar;
alter table mcp_servers add constraint mcp_servers_transport_check
  check (transport in ('streamable_http', 'sse', 'device'));
