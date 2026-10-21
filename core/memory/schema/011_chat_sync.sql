-- Atlas Chat — message transport ("chat:sync" in the design doc). Lets two already-paired
-- accounts (a confirmed, active row in atlas_chat_contact_relationships — see
-- 20261016190000_atlas_chat_pairing.sql) actually exchange ChatEnvelope bytes, instead of only
-- being able to establish a session with nowhere to send anything.
--
-- Scope: transport-level send/fetch/ack only (plan §12.3's "ACK de transporte"). The plan's
-- separate concept of an E2EE delivery receipt (a second encrypted message proving the far side
-- decrypted and persisted — "recibo E2EE") is NOT built here — see
-- docs/ATLAS_PEOPLE_UI_STATUS.md. Until that exists, this layer can only honestly support
-- "aceita pelo servidor", not "entregue".
--
-- T-06 enforcement lives here, not in the pairing migration: atlas_chat_send_envelope checks the
-- sender's active device against the device PINNED on the relationship at confirm time — if
-- either side's device/identity has changed since pairing (lost phone, re-registered device)
-- without a fresh pairing, sending fails instead of silently trusting a new identity.

create table if not exists public.atlas_chat_message_envelopes (
    id uuid primary key default gen_random_uuid(),
    conversation_id text not null check (pg_catalog.length(conversation_id) between 1 and 200),
    sender_user_id uuid not null references auth.users(id) on delete cascade,
    sender_device_id text not null references public.atlas_chat_devices(device_id) on delete cascade,
    recipient_user_id uuid not null references auth.users(id) on delete cascade,
    recipient_device_id text not null references public.atlas_chat_devices(device_id) on delete cascade,
    sequence bigint not null check (sequence >= 0),
    nonce_b64 text not null,
    ciphertext_b64 text not null,
    status text not null default 'pending' check (status in ('pending', 'delivered')),
    created_at timestamptz not null default now(),
    delivered_at timestamptz,
    -- Matches the plan's own client-side retry window ("cliente encerra retries em até sete
    -- dias") — an envelope nobody fetched in that window is considered abandoned.
    expires_at timestamptz not null default (now() + interval '7 days'),
    -- T-10/RF-08: the same logical message, resent after a crash, must land as the exact same
    -- row — never a duplicate, never silently overwritten by a different ciphertext for the same
    -- slot (plan §12.1: "Em conflito do mesmo identificador com ciphertext diferente, rejeitar").
    unique (conversation_id, sender_device_id, sequence)
);

create index if not exists idx_atlas_chat_message_envelopes_recipient_pending
    on public.atlas_chat_message_envelopes(recipient_device_id, sequence)
    where status = 'pending';

alter table public.atlas_chat_message_envelopes enable row level security;
revoke all on public.atlas_chat_message_envelopes from public, anon, authenticated;
grant all on public.atlas_chat_message_envelopes to service_role;
-- No authenticated grant at all, not even select: plan §14.1 is explicit envelopes need "API
-- restrita" that validates the device, not a bare `auth.uid()` RLS policy — every access goes
-- through the three RPCs below (all security definer), none of which is a direct table grant.

create or replace function public.atlas_chat_send_envelope(
    p_conversation_id text,
    p_sequence bigint,
    p_nonce_b64 text,
    p_ciphertext_b64 text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_sender_user_id uuid := auth.uid();
    v_sender_device_id text;
    v_user_low uuid;
    v_user_high uuid;
    v_device_low text;
    v_device_high text;
    v_relationship_status text;
    v_recipient_user_id uuid;
    v_recipient_device_id text;
    v_existing_ciphertext text;
begin
    if v_sender_user_id is null then
        raise exception 'not authenticated';
    end if;
    if p_conversation_id is null or pg_catalog.length(p_conversation_id) = 0 then
        raise exception 'invalid conversation id';
    end if;
    if p_sequence is null or p_sequence < 0 then
        raise exception 'invalid sequence';
    end if;
    if p_nonce_b64 is null or p_ciphertext_b64 is null then
        raise exception 'invalid envelope';
    end if;

    select device_id into v_sender_device_id
        from public.atlas_chat_devices
        where user_id = v_sender_user_id and status = 'active';
    if v_sender_device_id is null then
        raise exception 'no active device registered';
    end if;

    select user_low, user_high, device_low_id, device_high_id, status
        into v_user_low, v_user_high, v_device_low, v_device_high, v_relationship_status
        from public.atlas_chat_contact_relationships
        where invitation_id = p_conversation_id;
    if v_user_low is null then
        raise exception 'no pairing found for this conversation';
    end if;
    if v_relationship_status <> 'active' then
        raise exception 'this pairing is not active';
    end if;
    if v_sender_user_id not in (v_user_low, v_user_high) then
        raise exception 'not a participant in this conversation';
    end if;

    -- T-06: the sender's current active device must be the exact one pinned when this pairing
    -- was confirmed — a device/identity change since then means re-pairing is required before
    -- sending resumes, not a silent trust of whatever device is active now.
    if v_sender_user_id = v_user_low then
        if v_sender_device_id <> v_device_low then
            raise exception 'sender device does not match the pinned identity for this pairing';
        end if;
        v_recipient_user_id := v_user_high;
        v_recipient_device_id := v_device_high;
    else
        if v_sender_device_id <> v_device_high then
            raise exception 'sender device does not match the pinned identity for this pairing';
        end if;
        v_recipient_user_id := v_user_low;
        v_recipient_device_id := v_device_low;
    end if;

    select ciphertext_b64 into v_existing_ciphertext
        from public.atlas_chat_message_envelopes
        where conversation_id = p_conversation_id
          and sender_device_id = v_sender_device_id
          and sequence = p_sequence;
    if v_existing_ciphertext is not null then
        if v_existing_ciphertext <> p_ciphertext_b64 then
            raise exception 'conflicting envelope already exists for this sequence';
        end if;
        return; -- idempotent resend of the exact same envelope (RF-08) — nothing to do.
    end if;

    insert into public.atlas_chat_message_envelopes (
        conversation_id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id,
        sequence, nonce_b64, ciphertext_b64
    ) values (
        p_conversation_id, v_sender_user_id, v_sender_device_id, v_recipient_user_id, v_recipient_device_id,
        p_sequence, p_nonce_b64, p_ciphertext_b64
    );
end;
$$;

revoke all on function public.atlas_chat_send_envelope(text, bigint, text, text) from public, anon;
grant execute on function public.atlas_chat_send_envelope(text, bigint, text, text) to authenticated;

create or replace function public.atlas_chat_fetch_pending_envelopes()
returns table (
    id uuid,
    conversation_id text,
    sender_user_id uuid,
    sender_device_id text,
    sequence bigint,
    nonce_b64 text,
    ciphertext_b64 text,
    created_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_device_id text;
begin
    if v_user_id is null then
        raise exception 'not authenticated';
    end if;

    select device_id into v_device_id
        from public.atlas_chat_devices
        where user_id = v_user_id and status = 'active';
    if v_device_id is null then
        raise exception 'no active device registered';
    end if;

    return query
        select e.id, e.conversation_id, e.sender_user_id, e.sender_device_id, e.sequence, e.nonce_b64,
               e.ciphertext_b64, e.created_at
        from public.atlas_chat_message_envelopes e
        where e.recipient_device_id = v_device_id
          and e.status = 'pending'
          and e.expires_at > pg_catalog.now()
        order by e.sequence asc
        limit 200;
end;
$$;

revoke all on function public.atlas_chat_fetch_pending_envelopes() from public, anon;
grant execute on function public.atlas_chat_fetch_pending_envelopes() to authenticated;

create or replace function public.atlas_chat_ack_envelope(
    p_envelope_id uuid
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_user_id uuid := auth.uid();
    v_device_id text;
    v_deleted_id uuid;
begin
    if v_user_id is null then
        raise exception 'not authenticated';
    end if;

    select device_id into v_device_id
        from public.atlas_chat_devices
        where user_id = v_user_id and status = 'active';
    if v_device_id is null then
        raise exception 'no active device registered';
    end if;

    -- Deleted, not soft-marked: plan §12.3 — once processed, the envelope is released, nothing
    -- further to keep. Scoped to this device's own recipient_device_id and user_id together so
    -- acking someone else's envelope id (even if guessed) is a silent no-op, not an error that
    -- would leak whether that id exists.
    delete from public.atlas_chat_message_envelopes
        where id = p_envelope_id
          and recipient_device_id = v_device_id
          and recipient_user_id = v_user_id
        returning id into v_deleted_id;
end;
$$;

revoke all on function public.atlas_chat_ack_envelope(uuid) from public, anon;
grant execute on function public.atlas_chat_ack_envelope(uuid) to authenticated;

comment on table public.atlas_chat_message_envelopes is
    'In-flight encrypted message envelopes awaiting delivery; deleted on ack, never read/written directly by clients (only via the atlas_chat_send/fetch/ack_envelope RPCs).';
