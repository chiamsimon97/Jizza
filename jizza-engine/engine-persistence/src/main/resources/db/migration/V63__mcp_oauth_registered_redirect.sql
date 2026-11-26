-- Jizza remembers the return address it registered its own sign-in client with, so a client
-- registered for an old address (the callback base URL moved to another port or host) is
-- registered again on the next Sign in instead of sending the browser to the old address.
alter table mcp_servers add column oauth_registered_redirect text;

-- Kite servers that were registered before this column existed and never finished signing in:
-- drop that client so the next Sign in registers one for the current return address.
update mcp_servers
   set oauth_client_id = null, oauth_client_secret = null, updated_at = now()
 where auth_type = 'oauth'
   and access_token is null
   and oauth_client_id is not null
   and (url like 'https://mcp.kite.trade/%' or url = 'https://mcp.kite.trade');
