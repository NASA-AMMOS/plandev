/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Functions
  \ir functions/hasura/activity_preset_functions.sql
  \ir functions/hasura/delete_anchor_functions.sql
  \ir functions/hasura/hasura_functions.sql
  \ir functions/hasura/plan_branching_functions.sql
  \ir functions/hasura/plan_merge_functions.sql
  \ir functions/hasura/snapshot_functions.sql
  \ir functions/hasura/plan_migration_functions.sql

  -- Event Views
  \ir views/hasura/hasura_event_logs.sql
end;
