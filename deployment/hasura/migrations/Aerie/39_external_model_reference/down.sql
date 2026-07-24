alter table merlin.mission_model
  add column external_backend_url text,
  drop column external_backend,
  drop column external_model_key;

comment on column merlin.mission_model.external_backend_url is e''
  'For model_type="external": the HTTP endpoint of the backend that simulates this model '
  '(receives directives + config, returns simulation results). Null for JAR models.';

call migrations.mark_migration_rolled_back(39);
