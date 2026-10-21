-- Fixes a real bug in atlas_chat_register_device (20261016190000_atlas_chat_pairing.sql),
-- surfaced live as "Could not register this device" the SECOND time a device ever calls it — i.e.
-- on literally every pairing attempt after the very first one on a given install, since
-- ChatPairingRepository.ensureDeviceRegistered() (which calls this) runs at the start of every
-- pairing operation (createInvite, acceptInvite), not just once.
--
-- Root cause: device_id is a stable, deterministic value derived from the local identity's own
-- public key (see docs/ATLAS_CHAT_DESENHO_CRIPTOGRAFICO.md §9.1/§9.2) — it never changes across
-- calls on the same install. The original function revoked the caller's existing active device
-- row (by setting status='revoked', not deleting it) and then did a plain INSERT of a new row
-- with the same device_id. On the first call there's no existing row, so it worked. On every
-- later call, that INSERT collided with the primary key of the row the UPDATE just revoked
-- (device_id is `atlas_chat_devices`' primary key) — a unique-violation, raised to the client as
-- a generic registration failure.
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

    -- RF-02: registering a new device retires whichever OTHER device was active for this
    -- account. Excludes p_device_id itself — re-registering the exact same device (the common,
    -- repeated case) must stay idempotent, not revoke the very row the insert below is about to
    -- touch again.
    update public.atlas_chat_devices
        set status = 'revoked', revoked_at = pg_catalog.now()
        where user_id = v_user_id and status = 'active' and device_id <> p_device_id;

    insert into public.atlas_chat_devices (
        device_id, user_id, device_epoch, identity_public_key_b64, status, created_at
    ) values (
        p_device_id, v_user_id, p_device_epoch, p_identity_public_key_b64, 'active', pg_catalog.now()
    )
    -- Upsert, not a plain insert: this is what actually fixes the bug. Repeating this call for
    -- the same device_id (already present, possibly just revoked above if it belonged to a
    -- different user than expected) refreshes it in place and reactivates it, instead of
    -- colliding with the existing primary key.
    on conflict (device_id) do update set
        user_id = excluded.user_id,
        device_epoch = excluded.device_epoch,
        identity_public_key_b64 = excluded.identity_public_key_b64,
        status = 'active',
        revoked_at = null;
end;
$$;
