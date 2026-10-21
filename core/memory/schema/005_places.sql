-- User World Model (plan section 16): Places, the one new category added — "Devices"/"Apps" are
-- already fully known without a memory table (a single physical device, and AppRepository already
-- enumerates every installed app via PackageManager), and "Interests"/"Skills" are too speculative
-- for what this app actually does today (a task/reminder/automation assistant, not a long-term
-- personal knowledge base) to model without real demand. Places has clear, immediate use — "minha
-- academia é na Rua X", resolved later the same way memory_people already resolves a name.
-- Same shared metadata columns as every other memory_* table (see 001_init.sql's own doc comment).
create table if not exists memory_places (
    id uuid primary key default gen_random_uuid(),
    user_id text not null default 'default',
    nome text not null,                       -- e.g. 'trabalho', 'academia', 'casa dos pais'
    endereco text,                            -- freeform, not geocoded — this app has no mapping/geocoding of its own
    aliases text[] not null default '{}',     -- same shape as memory_projects.aliases
    origem text not null default 'user_explicit',
    confidence real not null default 1.0,
    importance real not null default 0.5,
    heat real not null default 0.0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    last_used_at timestamptz,
    expires_at timestamptz,
    confirmed_by_user boolean not null default true,
    valid boolean not null default true
);

create index if not exists idx_memory_places_user on memory_places(user_id) where valid;

do $$
begin
    if exists (select 1 from public.memory_places where user_id = 'default') then
        raise exception 'memory_places still contains user_id=default; assign legacy rows to a verified Auth user before enabling owner RLS';
    end if;
end $$;

alter table memory_places enable row level security;
do $$
declare
    existing_policy record;
begin
    for existing_policy in
        select policyname from pg_policies
        where schemaname = 'public' and tablename = 'memory_places'
    loop
        execute format('drop policy if exists %I on public.memory_places', existing_policy.policyname);
    end loop;
end $$;

create policy atlas_user_isolation on memory_places
    for all to authenticated
    using (((select auth.uid())::text = user_id))
    with check (((select auth.uid())::text = user_id));

revoke all on table public.memory_places from public, anon;
grant select, insert, update, delete on table public.memory_places to authenticated;
grant all on table public.memory_places to service_role;
