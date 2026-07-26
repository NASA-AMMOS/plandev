-- Record WHICH version of an external backend's model a mission_model row was registered against.
--
-- A JAR model is self-identifying: the bytes are stored, so the row and the thing it describes cannot
-- drift apart. An external model row holds only a *reference* (backend name + model key), and the thing
-- on the other end is redeployable. Nothing today notices when an operator redeploys an adapter whose
-- model now declares different activity or resource types: the stored activity_type/resource_type rows
-- silently describe a model that no longer exists, plans keep validating against them, and cached
-- simulation results stay "valid" because nothing in the cache key moved.
--
-- The backend already reports an identityHash over its declared type surface (activity types with their
-- parameters, plus resource schemas) via /introspect and /models. Merlin surfaces it at registration and
-- then throws it away. Storing it turns it into an attestation: this row was registered against a
-- backend that hashed to X.
--
-- Deliberately NOT a separate provenance table. Because any update to mission_model bumps `revision`
-- (increment_revision_mission_model_update), writing a *changed* hash here moves the model revision --
-- which simulation_dataset.model_revision already records against every result, and which
-- PostgresPlanRevisionData already compares to invalidate cached simulations. So one column gives both
-- result provenance and cache invalidation through machinery that already exists. Merlin only writes
-- the column when the value actually differs, so a re-introspection that finds no change is a no-op and
-- does not churn the revision.
--
-- Note what the hash does and does not cover: it is computed over the declared INTERFACE, so a pure
-- behavior change (same activity and resource types, different physics) does not move it. It detects
-- "the stored types are wrong", not "the answers changed".
alter table merlin.mission_model
  add column external_identity_hash text;

comment on column merlin.mission_model.external_identity_hash is e''
  'For model_type="external": the identityHash the backend reported for this model when it was last '
  'introspected -- a digest of its declared activity types, parameters, and resource schemas. Merlin '
  'compares the backend''s current hash against this before simulating, so a backend redeployed with a '
  'different type surface is detected instead of silently simulating against stale stored types. '
  'Updating it bumps the model revision, which stamps results and invalidates the simulation cache. '
  'Null for JAR models, and for external models registered before this was recorded.';

call migrations.mark_migration_applied(40);
