-- Support mission models with a non-JAR backend (e.g. an external / "foreign" model server).
--   1. Add a `model_type` discriminator. Existing rows default to 'jar', so current models keep working.
--   2. Make `jar_id` nullable, since a non-JAR model has no uploaded JAR file.

alter table merlin.mission_model
  add column model_type text not null default 'jar'
    constraint mission_model_type_check
      check (model_type = 'jar' or model_type = 'external'),
  alter column jar_id drop not null;

comment on column merlin.mission_model.model_type is e''
  'The kind of backend that defines this mission model.\n'
  '"jar" is a Java JAR uploaded to Aerie; "external" is a foreign model backend served outside Aerie.';
comment on column merlin.mission_model.jar_id is e''
  'An uploaded JAR file defining the mission model. Null for non-JAR (e.g. "external") models.';

call migrations.mark_migration_applied(37);
