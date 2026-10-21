create or replace function public.atlas_touch_encrypted_object_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at := pg_catalog.now();
    return new;
end;
$$;

drop trigger if exists atlas_encrypted_objects_touch_updated_at
    on public.atlas_encrypted_objects;
create trigger atlas_encrypted_objects_touch_updated_at
    before update on public.atlas_encrypted_objects
    for each row execute function public.atlas_touch_encrypted_object_updated_at();

comment on function public.atlas_touch_encrypted_object_updated_at() is
    'Updates the server timestamp whenever an opaque encrypted Atlas object changes.';
