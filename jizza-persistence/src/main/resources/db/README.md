# Migrations

* `V1__init.sql` — the Architecture Contract §1 schema, unchanged.
* `V2__platform.sql` — the tables and columns the Blueprint needs that the Contract does not name
  (durable job queue, ShedLock, outbox, LLM usage, OAuth state, password reset) plus hardening on
  Contract tables. Every addition is listed in `/docs/INTEGRATION-MANIFEST.md` under Contract gaps.

`V1` requires the `pgcrypto`, `citext` and `vector` extensions; `docker-compose.yml` uses the
`pgvector/pgvector` image, which ships all three.
