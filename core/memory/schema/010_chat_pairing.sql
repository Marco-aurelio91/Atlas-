-- Atlas Chat — device identity + presential pairing (claim/confirm) backend.
-- Scope: ONLY what core/chat-pairing's PairingInvitation/PairingAcceptance need to become usable
-- across two different accounts — device registration (RF-02), invite claim with a single-winner
-- guarantee (RF-03/T-04), and relationship confirmation that pins each side's device/identity
-- (RF-04/T-06). Message transport (envelopes, push, notification jobs — the plan's §13
-- `message_envelopes`/`push_installations`/`notification_jobs`) is a separate, later piece
-- (chat:sync), not built here. See docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md §9.
--
-- device_id/invitation_id are `text`, not `uuid`: core/chat-pairing's Kotlin types carry these as
-- plain String (generated via UUID.randomUUID().toString() in the real path, but nothing in that
-- layer enforces UUID format), so the DB column must not reject a well-formed value the client
-- considers valid.

create table if not exists public.atlas_chat_devices (
    device_id text primary key check (pg_catalog.length(device_id) between 1 and 200),
    user_id uuid not null references auth.users(id) on delete cascade,
    device_epoch bigint not null check (device_epoch > 0),
    identity_public_key_b64 text not null check (pg_catalog.length(identity_public_key_b64) > 0),
    status text not null default 'active' check (status in ('active', 'revoked')),
    created_at timestamptz not null default now(),
    revoked_at timestamptz
);

-- RF-02: exactly one active device per user.
create unique index if not exists idx_atlas_chat_devices_one_active_per_user
    on public.atlas_chat_devices(user_id)
    where status = 'active';

create index if not exists idx_atlas_chat_devices_user on public.atlas_chat_devices(user_id);

create table if not exists public.atlas_chat_contact_invites (
    invitation_id text primary key check (pg_catalog.length(invitation_id) between 1 and 200),
    creator_user_id uuid not null references auth.users(id) on delete cascade,
    creator_device_id text not null references public.atlas_chat_devices(device_id) on delete cascade,
    token_hash bytea not null,
    status text not null default 'pending' check (status in ('pending', 'claimed', 'confirmed', 'cancelled', 'expired')),
    claimed_by_user_id uuid references auth.users(id) on delete cascade,
    claimed_by_device_id text references public.atlas_chat_devices(device_id) on delete cascade,
    claimed_at timestamptz,
    confirmed_at timestamptz,
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    check (claimed_by_user_id is null or claimed_by_user_id <> creator_user_id)
);

create index if not exists idx_atlas_chat_contact_invites_creator
    on public.atlas_chat_contact_invites(creator_user_id, status);
create index if not exists idx_atlas_chat_contact_invites_claimed_by
    on public.atlas_chat_contact_invites(claimed_by_user_id) where claimed_by_user_id is not null;

create table if not exists public.atlas_chat_contact_relationships (
    id uuid primary key default gen_random_uuid(),
    user_low uuid not null references auth.users(id) on delete cascade,
    user_high uuid not null references auth.users(id) on delete cascade,
    epoch bigint not null default 1 check (epoch > 0),
    status text not null default 'active' check (status in ('active', 'removed', 'blocked')),
    -- Pinned at confirm time (RF-04) — a future identity/device change for either side no longer
    -- matches what's pinned here, which is what any later send/verify step must check against
    -- (that check lives in chat:sync's send_envelope, not here; this table only holds the pin).
    device_low_id text not null references public.atlas_chat_devices(device_id) on delete cascade,
    device_high_id text not null references public.atlas_chat_devices(device_id) on delete cascade,
    invitation_id text references public.atlas_chat_contact_invites(invitation_id) on delete set null,
    confirmed_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    check (user_low < user_high),
    unique (user_low, user_high)
);

create index if not exists idx_atlas_chat_contact_relationships_user_low
    on public.atlas_chat_contact_relationships(user_low);
create index if not exists idx_atlas_chat_contact_relationships_user_high
    on public.atlas_chat_contact_relationships(user_high);

-- RLS

alter table public.atlas_chat_devices enable row level security;
drop policy if exists atlas_chat_devices_select on public.atlas_chat_devices;
create policy atlas_chat_devices_select on public.atlas_chat_devices
    for select to authenticated
    using (
        user_id = (select auth.uid())
        -- Discovery once a pairing is active (plan §14.1: "Identidade pública remota: apenas
        -- descoberta autorizada por convite/contato" — never a general directory lookup).
        or exists (
            select 1 from public.atlas_chat_contact_relationships r
            where r.status = 'active'
              and (
                  (r.user_low = (select auth.uid()) and r.device_high_id = atlas_chat_devices.device_id)
                  or (r.user_high = (select auth.uid()) and r.device_low_id = atlas_chat_devices.device_id)
              )
        )
        -- Discovery mid-handshake, before a relationship row exists yet: each side needs the
        -- other's public key to compute its own copy of the presencial fingerprint (plan §8.3
        -- steps 4-5) before either one calls atlas_chat_confirm_contact_relationship.
        or exists (
            select 1 from public.atlas_chat_contact_invites i
            where i.status in ('claimed', 'confirmed')
              and (
                  (i.creator_user_id = (select auth.uid()) and i.claimed_by_device_id = atlas_chat_devices.device_id)
                  or (i.claimed_by_user_id = (select auth.uid()) and i.creator_device_id = atlas_chat_devices.device_id)
              )
        )
    );

revoke all on public.atlas_chat_devices from public, anon;
grant select on public.atlas_chat_devices to authenticated;
grant all on public.atlas_chat_devices to service_role;
-- No direct insert/update/delete grant: only atlas_chat_register_device (security definer) below
-- may write this table — a client cannot register a device for an account it doesn't hold, nor
-- revoke someone else's.

alter table public.atlas_chat_contact_invites enable row level security;
drop policy if exists atlas_chat_contact_invites_select on public.atlas_chat_contact_invites;
create policy atlas_chat_contact_invites_select on public.atlas_chat_contact_invites
    for select to authenticated
    using (
        creator_user_id = (select auth.uid())
        or claimed_by_user_id = (select auth.uid())
    );

revoke all on public.atlas_chat_contact_invites from public, anon;
grant select on public.atlas_chat_contact_invites to authenticated;
grant all on public.atlas_chat_contact_invites to service_role;
-- No direct insert/update/delete grant: create/claim/confirm only through the RPCs below — a
-- direct UPDATE could otherwise let a client confirm its own invite or overwrite a rival claim.

alter table public.atlas_chat_contact_relationships enable row level security;
drop policy if exists atlas_chat_contact_relationships_select on public.atlas_chat_contact_relationships;
create policy atlas_chat_contact_relationships_select on public.atlas_chat_contact_relationships
    for select to authenticated
    using ((select auth.uid()) in (user_low, user_high));

revoke all on public.atlas_chat_contact_relationships from public, anon;
grant select on public.atlas_chat_contact_relationships to authenticated;
grant all on public.atlas_chat_contact_relationships to service_role;

-- RPCs — see docs/ATLAS_CHAT_Plano_Completo_Criacao_e_Implantacao.md §14.2 for the contract each
-- one is modeling. Every caller's identity comes from auth.uid() inside the function, never from
-- a client-supplied parameter — a parameter would let one account act as another.

create or replace function public.atlas_chat_register_device(
    p_device_id text,
    p_identity_public_key_b64 text,
    p_device_epoch bigint
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        raise exception 'not authenticated';
    end if;
    if p_device_id is null or pg_catalog.length(p_device_id) = 0 then
        raise exception 'invalid device id';
    end if;
    if p_identity_public_key_b64 is null or pg_catalog.length(p_identity_public_key_b64) = 0 then
        raise exception 'invalid identity public key';
    end if;
    if p_device_epoch is null or p_device_epoch <= 0 then
        raise exception 'invalid device epoch';
    end if;

    -- RF-02: registering a new device retires whichever one was active for this account.
    update public.atlas_chat_devices
        set status = 'revoked', revoked_at = pg_catalog.now()
        where user_id = v_user_id and status = 'active';

    insert into public.atlas_chat_devices (
        device_id, user_id, device_epoch, identity_public_key_b64, status, created_at
    ) values (
        p_device_id, v_user_id, p_device_epoch, p_identity_public_key_b64, 'active', pg_catalog.now()
    );
end;
$$;

revoke all on function public.atlas_chat_register_device(text, text, bigint) from public, anon;
grant execute on function public.atlas_chat_register_device(text, text, bigint) to authenticated;

create or replace function public.atlas_chat_create_contact_invite(
    p_invitation_id text,
    p_token text,
    p_expires_at timestamptz
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_device_id text;
    v_pending_count integer;
begin
    if v_user_id is null then
        raise exception 'not authenticated';
    end if;
    if p_invitation_id is null or pg_catalog.length(p_invitation_id) = 0 then
        raise exception 'invalid invitation id';
    end if;
    -- 256 bits base64-encoded is 44 chars; 32 is a generous floor, not an exact check, since the
    -- server only ever sees/stores the hash, never the raw token's true length requirement.
    if p_token is null or pg_catalog.length(p_token) < 32 then
        raise exception 'invalid token';
    end if;
    if p_expires_at is null or p_expires_at <= pg_catalog.now()
        or p_expires_at > pg_catalog.now() + interval '15 minutes' then
        raise exception 'invalid expiry';
    end if;

    select device_id into v_device_id
        from public.atlas_chat_devices
        where user_id = v_user_id and status = 'active';
    if v_device_id is null then
        raise exception 'no active device registered';
    end if;

    select pg_catalog.count(*) into v_pending_count
        from public.atlas_chat_contact_invites
        where creator_user_id = v_user_id and status = 'pending' and expires_at > pg_catalog.now();
    if v_pending_count >= 5 then
        raise exception 'too many pending invites';
    end if;

    -- The raw token never lands in storage — only its hash (plan §8.2: "Servidor guarda seu
    -- hash"). sha256() here is the builtin added in PostgreSQL 14 (pg_catalog), not pgcrypto's
    -- digest() — avoids depending on which schema pgcrypto's extension functions were installed
    -- into on this project.
    insert into public.atlas_chat_contact_invites (
        invitation_id, creator_user_id, creator_device_id, token_hash, status, expires_at, created_at
    ) values (
        p_invitation_id, v_user_id, v_device_id,
        pg_catalog.sha256(pg_catalog.convert_to(p_token, 'UTF8')),
        'pending', p_expires_at, pg_catalog.now()
    );
end;
$$;

revoke all on function public.atlas_chat_create_contact_invite(text, text, timestamptz) from public, anon;
grant execute on function public.atlas_chat_create_contact_invite(text, text, timestamptz) to authenticated;

create or replace function public.atlas_chat_claim_contact_invite(
    p_invitation_id text,
    p_token text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_device_id text;
    v_creator_user_id uuid;
    v_updated_id text;
begin
    if v_user_id is null then
        raise exception 'not authenticated';
    end if;
    if p_invitation_id is null or p_token is null then
        raise exception 'invalid claim';
    end if;

    select device_id into v_device_id
        from public.atlas_chat_devices
        where user_id = v_user_id and status = 'active';
    if v_device_id is null then
        raise exception 'no active device registered';
    end if;

    select creator_user_id into v_creator_user_id
        from public.atlas_chat_contact_invites
        where invitation_id = p_invitation_id;
    if v_creator_user_id = v_user_id then
        raise exception 'cannot claim your own invite';
    end if;

    -- T-04: only the first caller to reach this UPDATE while the row is still 'pending' (token
    -- matching, not expired) actually changes it — a second concurrent claim matches zero rows
    -- and falls through to the exception below instead of overwriting the first winner.
    update public.atlas_chat_contact_invites
        set status = 'claimed',
            claimed_by_user_id = v_user_id,
            claimed_by_device_id = v_device_id,
            claimed_at = pg_catalog.now()
        where invitation_id = p_invitation_id
          and status = 'pending'
          and expires_at > pg_catalog.now()
          and token_hash = pg_catalog.sha256(pg_catalog.convert_to(p_token, 'UTF8'))
        returning invitation_id into v_updated_id;

    if v_updated_id is null then
        raise exception 'invite not claimable (expired, wrong token, already claimed, or not found)';
    end if;
end;
$$;

revoke all on function public.atlas_chat_claim_contact_invite(text, text) from public, anon;
grant execute on function public.atlas_chat_claim_contact_invite(text, text) to authenticated;

create or replace function public.atlas_chat_confirm_contact_relationship(
    p_invitation_id text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_creator_user_id uuid;
    v_claimed_by_user_id uuid;
    v_creator_device_id text;
    v_claimed_by_device_id text;
    v_status text;
    v_low uuid;
    v_high uuid;
    v_device_low text;
    v_device_high text;
begin
    if v_user_id is null then
        raise exception 'not authenticated';
    end if;

    select creator_user_id, claimed_by_user_id, creator_device_id, claimed_by_device_id, status
        into v_creator_user_id, v_claimed_by_user_id, v_creator_device_id, v_claimed_by_device_id, v_status
        from public.atlas_chat_contact_invites
        where invitation_id = p_invitation_id
        for update;

    if v_creator_user_id is null then
        raise exception 'invite not found';
    end if;
    if v_creator_user_id <> v_user_id then
        raise exception 'only the invite creator can confirm it';
    end if;
    if v_status <> 'claimed' or v_claimed_by_user_id is null or v_claimed_by_device_id is null then
        raise exception 'invite has not been claimed yet';
    end if;

    update public.atlas_chat_contact_invites
        set status = 'confirmed', confirmed_at = pg_catalog.now()
        where invitation_id = p_invitation_id;

    if v_creator_user_id < v_claimed_by_user_id then
        v_low := v_creator_user_id; v_high := v_claimed_by_user_id;
        v_device_low := v_creator_device_id; v_device_high := v_claimed_by_device_id;
    else
        v_low := v_claimed_by_user_id; v_high := v_creator_user_id;
        v_device_low := v_claimed_by_device_id; v_device_high := v_creator_device_id;
    end if;

    -- A second pairing between the same two accounts bumps `epoch` and re-pins both devices
    -- instead of creating a duplicate row — `unique (user_low, user_high)` makes this the only
    -- relationship between them there will ever be.
    insert into public.atlas_chat_contact_relationships as rel (
        user_low, user_high, epoch, status, device_low_id, device_high_id, invitation_id, confirmed_at, created_at
    ) values (
        v_low, v_high, 1, 'active', v_device_low, v_device_high, p_invitation_id, pg_catalog.now(), pg_catalog.now()
    )
    on conflict (user_low, user_high) do update set
        epoch = rel.epoch + 1,
        status = 'active',
        device_low_id = excluded.device_low_id,
        device_high_id = excluded.device_high_id,
        invitation_id = excluded.invitation_id,
        confirmed_at = pg_catalog.now();
end;
$$;

revoke all on function public.atlas_chat_confirm_contact_relationship(text) from public, anon;
grant execute on function public.atlas_chat_confirm_contact_relationship(text) to authenticated;

comment on table public.atlas_chat_devices is
    'One row per currently-or-previously-active chat device identity; identity_public_key_b64 is public material, never a secret.';
comment on table public.atlas_chat_contact_invites is
    'Presential pairing invites (NFC/QR). Only the token hash is stored, never the raw token.';
comment on table public.atlas_chat_contact_relationships is
    'Confirmed pairings between two accounts, with each side''s device/identity pinned at confirm time.';
