-- Opaque client-encrypted objects. This table is intentionally not wired to MemoryRepository yet:
-- key recovery and migration of existing plaintext rows must be implemented first.
create table if not exists atlas_encrypted_objects (
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
    on atlas_encrypted_objects(user_id, updated_at desc);

alter table atlas_encrypted_objects enable row level security;
drop policy if exists atlas_encrypted_objects_owner on atlas_encrypted_objects;
create policy atlas_encrypted_objects_owner on atlas_encrypted_objects
    for all to authenticated
    using ((select auth.uid()) = user_id)
    with check ((select auth.uid()) = user_id);

revoke all on atlas_encrypted_objects from public, anon;
grant select, insert, update, delete on atlas_encrypted_objects to authenticated;
grant all on atlas_encrypted_objects to service_role;

create or replace function atlas_touch_encrypted_object_updated_at()
returns trigger language plpgsql set search_path = '' as $$
begin
    new.updated_at := pg_catalog.now();
    return new;
end;
$$;

drop trigger if exists atlas_encrypted_objects_touch_updated_at on atlas_encrypted_objects;
create trigger atlas_encrypted_objects_touch_updated_at
    before update on atlas_encrypted_objects
    for each row execute function atlas_touch_encrypted_object_updated_at();

comment on table atlas_encrypted_objects is
    'Opaque client-encrypted Atlas payloads; envelope JSON must contain no plaintext.';
