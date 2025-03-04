/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Tables
  \ir tables/ui/extensions.sql
  \ir tables/ui/extension_roles.sql
  \ir tables/ui/view.sql
end;
