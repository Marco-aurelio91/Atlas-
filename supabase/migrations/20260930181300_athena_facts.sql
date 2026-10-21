-- A fact about an entity, with validity window and confidence (docs §10). confidence stays null
-- until a real calibrated calculation exists — never fabricated (see the Confidence Engine, §29).
create table athena.world_facts (
    id uuid primary key default gen_random_uuid(),
    entity_id uuid references athena.world_entities(id),
    predicate text not null,
    value jsonb not null,
    effective_from timestamptz,
    effective_until timestamptz,
    confidence numeric,
    freshness text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

revoke all on athena.world_facts from anon, authenticated;
