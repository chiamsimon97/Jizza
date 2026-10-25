-- Threads are something the user turns on for a chat, not how every chat works. A main chat with
-- threads on lets Jizza move topics into specialist-led threads; one without them is a plain chat.
alter table conversations add column threads_enabled boolean not null default false;

-- Chats that already have threads keep working the way they did.
update conversations c set threads_enabled = true
where c.kind = 'main' and exists (select 1 from conversations t where t.parent_id = c.id);
