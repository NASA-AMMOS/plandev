-- Revert non-JAR mission model support.
-- Any model without a JAR must be removed first, since `jar_id` becomes NOT NULL again.
delete from merlin.mission_model where jar_id is null;

alter table merlin.mission_model
  alter column jar_id set not null,
  drop constraint mission_model_type_check,
  drop column model_type;

comment on column merlin.mission_model.jar_id is e''
  'An uploaded JAR file defining the mission model.';

call migrations.mark_migration_rolled_back(37);
