create table if not exists atlas_key_recovery_envelopes (
    user_id uuid primary key references auth.users(id) on delete cascade,
    key_id uuid not null,
    crypto_version smallint not null check (crypto_version = 1),
    envelope jsonb not null check (jsonb_typeof(envelope) = 'object'),
    created_at timestamptz not null default now()
);

alter table atlas_key_recovery_envelopes enable row level security;
drop trigger if exists atlas_key_recovery_envelopes_touch_updated_at on atlas_key_recovery_envelopes;
drop policy if exists atlas_key_recovery_envelopes_read_owner on atlas_key_recovery_envelopes;
drop policy if exists atlas_key_recovery_envelopes_insert_owner on atlas_key_recovery_envelopes;
drop policy if exists atlas_key_recovery_envelopes_owner on atlas_key_recovery_envelopes;
create policy atlas_key_recovery_envelopes_read_owner on atlas_key_recovery_envelopes
    for select to authenticated using (auth.uid() = user_id);
create policy atlas_key_recovery_envelopes_insert_owner on atlas_key_recovery_envelopes
    for insert to authenticated with check (auth.uid() = user_id);

revoke all on atlas_key_recovery_envelopes from public, anon;
grant select, insert on atlas_key_recovery_envelopes to authenticated;
grant all on atlas_key_recovery_envelopes to service_role;
