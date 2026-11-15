-- A device that has once sent a message signed with its device key must sign every message from
-- then on: a stolen access token alone can no longer speak for it (web automation plan 05, P1).
ALTER TABLE devices ADD COLUMN signs_messages boolean NOT NULL DEFAULT false;
