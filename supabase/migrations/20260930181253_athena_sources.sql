-- External sources ATHENA can query (docs §8). Examples: OPEN_METEO, INMET, TMDB, BCB, IBGE,
-- GOOGLE_PLACES, WEB_SEARCH.
create table athena.world_sources (
    id uuid primary key default gen_random_uuid(),
    code text not null unique,
    name text not null,
    source_type text not null,
    domain text not null,
    authority_level text,
    base_url text,
    enabled boolean not null default true,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

revoke all on athena.world_sources from anon, authenticated;
