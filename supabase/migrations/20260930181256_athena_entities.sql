-- Things that exist in the world (docs §9). Examples: "BR-267", "Motorola Mobility", "São Paulo",
-- "Flight LA1234". Never a person or a personal reference — that stays in ATLAS.
create table athena.world_entities (
    id uuid primary key default gen_random_uuid(),
    entity_type text not null,
    canonical_name text not null,
    external_ids jsonb not null default '{}'::jsonb,
    aliases jsonb not null default '[]'::jsonb,
    attributes jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index idx_world_entities_type on athena.world_entities(entity_type);

revoke all on athena.world_entities from anon, authenticated;
