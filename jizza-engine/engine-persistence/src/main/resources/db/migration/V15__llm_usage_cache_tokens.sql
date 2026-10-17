-- Prompt caching: the provider reads part of a request from its cache at a fraction of the input
-- price and writes part of it at a little more. input_tokens still counts every input token; these
-- say how much of it was cached, so spend is priced correctly and the cache hit rate is visible.
-- One column per statement, so the schema check can read each of them.
alter table llm_usage add column cache_read_tokens integer not null default 0;
alter table llm_usage add column cache_write_tokens integer not null default 0;
