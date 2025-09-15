/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Tables
  \ir tables/deliveries/target.sql
  \ir tables/deliveries/delivery.sql
  \ir tables/deliveries/file_to_delivery.sql
  \ir tables/deliveries/action_to_target.sql
  \ir tables/deliveries/action_to_delivery.sql
end;
