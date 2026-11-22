-- Whether an agent may search the web and read pages with the model provider's own tools (chat
-- plan C5). On by default, like every other chat in the market; the user can turn it off per agent.
ALTER TABLE agents ADD COLUMN web_access boolean NOT NULL DEFAULT true;
