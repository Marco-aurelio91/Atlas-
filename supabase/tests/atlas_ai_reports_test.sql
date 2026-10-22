begin;

create extension if not exists pgtap with schema extensions;

select extensions.plan(4);

insert into auth.users (
    id, instance_id, aud, role, email, encrypted_password,
    email_confirmed_at, raw_app_meta_data, raw_user_meta_data, created_at, updated_at
) values
    ('60000000-0000-4000-8000-000000000006', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'atlas-report-a@example.invalid', '', now(), '{}'::jsonb, '{}'::jsonb, now(), now()),
    ('70000000-0000-4000-8000-000000000007', '00000000-0000-0000-0000-000000000000', 'authenticated', 'authenticated', 'atlas-report-b@example.invalid', '', now(), '{}'::jsonb, '{}'::jsonb, now(), now());

set local role authenticated;
select set_config('request.jwt.claim.sub', '60000000-0000-4000-8000-000000000006', true);

select extensions.lives_ok(
    $$ insert into public.atlas_ai_reports (user_id, reason, response_excerpt)
       values ('60000000-0000-4000-8000-000000000006', 'harmful', 'resposta de teste') $$,
    'an authenticated user can report an AI response as themselves'
);
select extensions.throws_ok(
    $$ insert into public.atlas_ai_reports (user_id, reason, response_excerpt)
       values ('70000000-0000-4000-8000-000000000007', 'harmful', 'resposta de teste') $$,
    '42501', null,
    'a user cannot file a report on behalf of another account'
);
select extensions.throws_ok(
    $$ insert into public.atlas_ai_reports (user_id, reason, response_excerpt)
       values ('60000000-0000-4000-8000-000000000006', 'not-a-reason', 'resposta de teste') $$,
    '23514', null,
    'an unknown report reason is rejected'
);

select set_config('request.jwt.claim.sub', '70000000-0000-4000-8000-000000000007', true);
select extensions.is(
    (select count(*) from public.atlas_ai_reports),
    0::bigint,
    'another user cannot read reports filed by someone else'
);

select * from extensions.finish();
rollback;
