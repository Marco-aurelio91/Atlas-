begin;

create extension if not exists pgtap with schema extensions;

select extensions.plan(5);

insert into auth.users (
    id, instance_id, aud, role, email, encrypted_password,
    email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at
) values
    ('80000000-0000-4000-8000-000000000008', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'atlas-plan-free@example.invalid', '', now(), '{}'::jsonb, '{}'::jsonb, now(), now()),
    ('90000000-0000-4000-8000-000000000009', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'atlas-plan-paid@example.invalid', '', now(), '{}'::jsonb, '{}'::jsonb, now(), now());

insert into public.atlas_user_plans (user_id, plan)
values ('90000000-0000-4000-8000-000000000009', 'paid');

-- All calls below fall in the same minute-window budget (20), so only the daily cap differs.
select extensions.is(
    (select count(*) from generate_series(1, 15) g
        where public.atlas_claim_ai_quota('80000000-0000-4000-8000-000000000008', 'chat')),
    15::bigint,
    'a free user gets 15 chat messages per day'
);
select extensions.is(
    public.atlas_claim_ai_quota('80000000-0000-4000-8000-000000000008', 'chat'),
    false,
    'the 16th chat message of the day is refused on the free plan'
);
select extensions.is(
    (select count(*) from generate_series(1, 16) g
        where public.atlas_claim_ai_quota('90000000-0000-4000-8000-000000000009', 'chat')),
    16::bigint,
    'a paid user is still allowed past the free daily cap'
);
select extensions.is(
    (select plan from public.atlas_user_plans where user_id = '90000000-0000-4000-8000-000000000009'),
    'paid',
    'the paid plan row is stored'
);

set local role authenticated;
select set_config('request.jwt.claim.sub', '80000000-0000-4000-8000-000000000008', true);
select extensions.throws_ok(
    $$ update public.atlas_user_plans set plan = 'paid' where user_id = '80000000-0000-4000-8000-000000000008' $$,
    '42501', null,
    'a client cannot upgrade its own plan'
);

select * from extensions.finish();
rollback;
