/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Create Non-Public Schemas
  \ir schemas.sql

  -- Migrations
  \ir tables/migrations/schema_migrations.sql
  \ir applied_migrations.sql
  \ir views/migrations/applied_migrations_view.sql

  -- Util Functions
  \ir functions/util_functions/shared_update_functions.sql
  \ir types/util_functions/request_status.sql

  -- Permissions
  \ir init_permissions.sql

  -- Tags Part 1 (Objects created here due to dependency in Merlin schema)
  \ir tables/tags/tags.sql
  \ir functions/tags/get_tags.sql

  -- UI
  \ir init_ui.sql

  -- Merlin
  \ir init_merlin.sql

  -- Scheduling
  \ir init_scheduler_post_merlin.sql

  -- Sequencing
  \ir init_sequencing.sql

  -- Tags
  \ir init_tags.sql

  -- Hasura
  \ir init_hasura.sql

  -- Preload Data
  \ir default_user_roles.sql;

  -- Initialize DB User permissions
  \ir init_db_users.sql

  -- Actions
  \ir init_actions.sql
end;
