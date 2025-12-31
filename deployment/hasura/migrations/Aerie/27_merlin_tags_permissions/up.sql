do $$
  declare
    merlin_user text;
  begin
    select grantee
    from information_schema.role_table_grants
    where table_schema = 'merlin'
      and table_name = 'constraint_specification'
      and privilege_type = 'INSERT'
      and grantee != (select current_user)
    limit 1
    into merlin_user;

    execute format('grant usage on schema tags to %I', merlin_user);
    execute format('grant select, insert on table tags.tags to %I', merlin_user);
  end
$$;

call migrations.mark_migration_applied(27);
