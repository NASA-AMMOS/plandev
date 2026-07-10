-- The endpoint of the external backend that simulates a non-JAR ("external") mission model.
-- Merlin's simulation route POSTs the plan's directives + config here and ingests the returned results.
alter table merlin.mission_model
  add column external_backend_url text;

comment on column merlin.mission_model.external_backend_url is e''
  'For model_type="external": the HTTP endpoint of the backend that simulates this model '
  '(receives directives + config, returns simulation results). Null for JAR models.';

call migrations.mark_migration_applied(38);
