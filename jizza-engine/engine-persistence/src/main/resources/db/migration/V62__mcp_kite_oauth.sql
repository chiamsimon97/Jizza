-- Zerodha's hosted Kite MCP server (mcp.kite.trade) now turns away any request without an OAuth
-- bearer token ("no bearer token", 401 with a resource_metadata pointer), so servers added
-- with no sign-in can no longer start a session. They move to the MCP sign-in flow (discovery,
-- client registration, PKCE): the person presses Sign in once on the server's page. The tools
-- already learned are kept; nothing else about the server changes.
update mcp_servers
   set auth_type = 'oauth', status = 'needs_auth', last_error = null, updated_at = now()
 where auth_type = 'none'
   and transport = 'streamable_http'
   and (url like 'https://mcp.kite.trade/%' or url = 'https://mcp.kite.trade');
