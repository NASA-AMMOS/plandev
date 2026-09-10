-- Revert JAR-less mission model support.
--
-- Any model without a JAR must go first: `jar_id` becomes NOT NULL again, and there is nothing to
-- put there. Cascades take their plans, simulations and datasets with them, which is the honest
-- consequence of rolling this back -- a declared model's run cannot be represented at all without
-- these columns.
delete from merlin.mission_model where jar_id is null;

alter table merlin.mission_model
  drop constraint mission_model_type_check,
  drop column model_type,
  drop column external_identity_hash,
  drop column external_capabilities,
  alter column jar_id set not null;

comment on column merlin.mission_model.jar_id is e''
  'An uploaded JAR file defining the mission model.';

-- The procedure is mark_migration_rolled_back, not mark_migration_rollback. Migrations 40 and 41 on
-- feat/foreign-model-backend call the latter and both fail on the way down; see
-- schema_migrations.sql.
call migrations.mark_migration_rolled_back(38);
