-- World-query cache, keyed by the query itself — never by user identity (docs §14, §31).
-- Correct:   weather:-21.95:-44.89:2026-09-30T18
-- Incorrect: user123:weather
create table athena.world_cache (
    cache_key text primary key,
    intent text not null,
    payload jsonb not null,
    source_codes text[] not null default '{}',
    confidence numeric,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null
);

create index idx_world_cache_expires_at on athena.world_cache(expires_at);

revoke all on athena.world_cache from anon, authenticated;
