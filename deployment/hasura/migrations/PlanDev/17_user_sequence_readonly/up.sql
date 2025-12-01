alter table sequencing.user_sequence
add column is_locked boolean not null default false;

comment on column sequencing.user_sequence.is_locked is e''
  'A boolean representing whether this user sequence is editable.';

-- Dropping trigger because check_locked_update includes updating the timestamp
drop trigger set_timestamp on sequencing.user_sequence;

create function sequencing.check_is_locked_update()
returns trigger
language plpgsql as $$
begin
  if old.is_locked and new.is_locked then
    raise exception 'Cannot update locked user sequence.';
  end if;

  -- Update the updated_at timestamp
  new.updated_at = now();
  return new;
end
$$;

create trigger check_locked_update
before update on sequencing.user_sequence
for each row
execute function sequencing.check_is_locked_update();

create function sequencing.check_is_locked_delete()
returns trigger
language plpgsql as $$
begin
  if old.is_locked then
    raise exception 'Cannot delete locked user sequence.';
  end if;

  return old;
end
$$;

create trigger check_locked_delete
before delete on sequencing.user_sequence
for each row
execute function sequencing.check_is_locked_delete();

call migrations.mark_migration_applied('17');
