/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Domain types
  \ir types/permissions/permissions.sql

  -- Tables
  \ir tables/permissions/user_roles.sql
  \ir tables/permissions/user_role_permission.sql
  \ir tables/permissions/users.sql
  \ir tables/permissions/users_allowed_roles.sql

  -- Views
  \ir views/permissions/users_and_roles.sql
  \ir views/permissions/role_members.sql

  -- Functions
  \ir functions/permissions/get_role.sql
  \ir functions/permissions/get_function_permissions.sql
  \ir functions/permissions/check_general_permissions.sql
  \ir functions/permissions/merge_permissions.sql
end;
