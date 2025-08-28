create function permissions.get_function_permissions(_function permissions.function_permission_key, hasura_session json)
returns permissions.permission
stable
language plpgsql as $$
declare
  _role text;
  _function_permission permissions.permission;
begin
  _role := permissions.get_role(hasura_session);
  -- The aerie_admin role is always treated as having NO_CHECK permissions on all functions.
  if _role = 'aerie_admin' then return 'NO_CHECK'; end if;

  select (function_permissions ->> _function::text)::permissions.permission
  from permissions.user_role_permission urp
  where urp.role = _role
  into _function_permission;

  -- The absence of the function key means that the role does not have permission to perform the function.
  if _function_permission is null then
    raise insufficient_privilege
      using message = 'User with role '''|| _role ||''' is not permitted to run '''|| _function ||'''';
  end if;

  return _function_permission::permissions.permission;
end
$$;
