begin;

create extension if not exists pgtap with schema extensions;

select extensions.plan(12);

insert into auth.users (
    id, instance_id, aud, role, email, encrypted_password,
    email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at
) values
    ('10000000-0000-4000-8000-000000000001', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'atlas-rls-a@example.invalid', '', now(), '{}'::jsonb, '{}'::jsonb, now(), now()),
    ('20000000-0000-4000-8000-000000000002', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'atlas-rls-b@example.invalid', '', now(), '{}'::jsonb, '{}'::jsonb, now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);

select extensions.lives_ok(
    $$ insert into public.atlas_encrypted_objects (user_id, object_id, object_type, crypto_version, key_id, envelope)
       values ('10000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000003', 'memory', 1, 'test-key', '{"v":1,"ciphertext":"opaque"}'::jsonb) $$,
    'an authenticated owner can insert an encrypted object'
);
select extensions.is(
    (select count(*) from public.atlas_encrypted_objects where object_id = '30000000-0000-4000-8000-000000000003'),
    1::bigint,
    'the owner can read their encrypted object'
);

select set_config('request.jwt.claim.sub', '20000000-0000-4000-8000-000000000002', true);
select extensions.is(
    (select count(*) from public.atlas_encrypted_objects where object_id = '30000000-0000-4000-8000-000000000003'),
    0::bigint,
    'another authenticated user cannot read the object'
);
select extensions.lives_ok(
    $$ do $test$ begin
         update public.atlas_encrypted_objects set envelope = '{"v":1,"ciphertext":"tampered"}'::jsonb
         where object_id = '30000000-0000-4000-8000-000000000003';
         delete from public.atlas_encrypted_objects
         where object_id = '30000000-0000-4000-8000-000000000003';
       end $test$ $$,
    'another authenticated user cannot update or delete the object'
);
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
select extensions.is(
    (select envelope ->> 'ciphertext' from public.atlas_encrypted_objects where object_id = '30000000-0000-4000-8000-000000000003'),
    'opaque',
    'the owner object remains unchanged after another user attempts mutation'
);
select set_config('request.jwt.claim.sub', '20000000-0000-4000-8000-000000000002', true);
select extensions.throws_ok(
    $$ insert into public.atlas_encrypted_objects (user_id, object_id, object_type, crypto_version, key_id, envelope)
       values ('10000000-0000-4000-8000-000000000001', '40000000-0000-4000-8000-000000000004', 'memory', 1, 'test-key', '{"v":1}'::jsonb) $$,
    '42501', null,
    'a user cannot insert an object for another account'
);

select extensions.throws_ok(
    $$ insert into public.atlas_key_recovery_envelopes (user_id, key_id, crypto_version, envelope)
       values ('10000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000005', 1, '{"v":1,"ciphertext":"opaque"}'::jsonb) $$,
    '42501', null,
    'a user cannot upload another account recovery envelope'
);
select extensions.is(
    (select count(*) from public.atlas_key_recovery_envelopes where user_id = '10000000-0000-4000-8000-000000000001'),
    0::bigint,
    'another user cannot read a recovery envelope'
);

select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
select extensions.lives_ok(
    $$ insert into public.atlas_key_recovery_envelopes (user_id, key_id, crypto_version, envelope)
       values ('10000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000005', 1, '{"v":1,"ciphertext":"opaque"}'::jsonb) $$,
    'the owner can upload their recovery envelope'
);
select extensions.throws_ok(
    $$ update public.atlas_key_recovery_envelopes set envelope = '{"v":1,"ciphertext":"changed"}'::jsonb
       where user_id = '10000000-0000-4000-8000-000000000001' $$,
    '42501', null,
    'a client cannot replace an immutable recovery envelope'
);
select extensions.throws_ok(
    $$ delete from public.atlas_key_recovery_envelopes where user_id = '10000000-0000-4000-8000-000000000001' $$,
    '42501', null,
    'a client cannot delete a recovery envelope through the public API'
);

reset role;
set local role anon;
select extensions.throws_ok(
    $$ select * from public.atlas_encrypted_objects $$,
    '42501', null,
    'anonymous clients cannot access encrypted objects'
);

select * from extensions.finish();
rollback;
