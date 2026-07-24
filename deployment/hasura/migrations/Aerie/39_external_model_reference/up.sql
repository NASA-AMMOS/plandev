-- Replace the single `external_backend_url` with a backend *reference*: a backend name + model key.
-- The mission_model row now names a trusted, operator-configured backend and the key of the model it
-- hosts, instead of a raw URL. Merlin resolves the reference to the backend's real URL from its
-- EXTERNAL_MODEL_BACKENDS config at introspect/simulate/validate time. This lets external models be
-- created through the ordinary Hasura insert path -- the insert's existing refresh* event triggers drive
-- introspection -- so merlin no longer inserts the row itself (removing its need for hdb_catalog perms),
-- and it keeps backend URLs out of user-supplied data (no SSRF surface).
alter table merlin.mission_model
  add column external_backend text,
  add column external_model_key text,
  drop column external_backend_url;

comment on column merlin.mission_model.external_backend is e''
  'For model_type="external": the name of a trusted backend declared in merlin''s EXTERNAL_MODEL_BACKENDS '
  'config. Merlin resolves this name to the backend''s URL. Null for JAR models.';
comment on column merlin.mission_model.external_model_key is e''
  'For model_type="external": the key selecting which model to use on its backend (a backend may host '
  'several). Null for JAR models.';

call migrations.mark_migration_applied(39);
