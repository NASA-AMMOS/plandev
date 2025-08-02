create or replace function scheduler.increment_spec_revision_on_conditions_spec_delete()
  returns trigger
  security definer
language plpgsql as $$
begin
  update scheduler.scheduling_specification
  set revision = revision + 1
  where id = new.specification_id;
  return new;
end;
$$;
call migrations.mark_migration_rolled_back('13');
