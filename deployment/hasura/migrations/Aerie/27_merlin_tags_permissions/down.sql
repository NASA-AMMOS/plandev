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

    execute format('revoke select, insert on table tags.tags from %I', merlin_user);
    execute format('revoke usage on schema tags from %I', merlin_user);
  end
$$;

call migrations.mark_migration_rolled_back(27);
