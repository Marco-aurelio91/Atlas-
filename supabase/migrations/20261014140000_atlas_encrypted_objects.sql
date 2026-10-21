create table if not exists public.atlas_encrypted_objects (
    user_id uuid not null references auth.users(id) on delete cascade,
    object_id uuid not null,
    object_type text not null check (object_type in ('memory', 'conversation', 'routine', 'note', 'attachment')),
    crypto_version smallint not null check (crypto_version > 0),
    key_id text not null,
    envelope jsonb not null check (jsonb_typeof(envelope) = 'object'),
    revision bigint not null default 1 check (revision > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (user_id, object_id)
);

create index if not exists idx_atlas_encrypted_objects_user_updated
    on public.atlas_encrypted_objects(user_id, updated_at desc);

alter table public.atlas_encrypted_objects enable row level security;
drop policy if exists atlas_encrypted_objects_owner on public.atlas_encrypted_objects;
create policy atlas_encrypted_objects_owner on public.atlas_encrypted_objects
    for all to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

revoke all on public.atlas_encrypted_objects from public, anon;
grant select, insert, update, delete on public.atlas_encrypted_objects to authenticated;
grant all on public.atlas_encrypted_objects to service_role;

comment on table public.atlas_encrypted_objects is
    'Opaque client-encrypted Atlas payloads; envelope JSON must contain no plaintext.';
