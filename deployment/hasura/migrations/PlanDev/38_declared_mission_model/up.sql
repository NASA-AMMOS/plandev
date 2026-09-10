-- A mission model that PlanDev never compiled and cannot run.
--
-- Today every mission_model row IS a JAR: `jar_id` is not null, and merlin classloads it to answer
-- every question about the model's shape. That forecloses a capability PlanDev otherwise has no way
-- to offer -- importing a simulation somebody else already performed, as a first-class run. Such a
-- run arrives with its own type declaration (activity types with parameters, resource schemas,
-- configuration parameters) and its own results, and there is nothing to compile and nothing to run.
--
-- Three changes, each smaller than it looks:
--
--   1. `model_type` discriminates how a model is backed. Existing rows default to 'jar', so every
--      model that exists keeps working with no data migration. Written as `in ('jar','declared')`
--      rather than as two equality tests deliberately: a third kind is already designed (a live
--      external backend), and widening a list is a one-token change.
--
--   2. `jar_id` becomes nullable, which is the actual enabling change. Note the FK survives: a null
--      foreign key is permitted, so a declared model simply references nothing.
--
--   3. Two columns that record what PlanDev was told, as opposed to what it derived.
--
-- The read paths that touched `uploaded_file` with an INNER JOIN become LEFT JOINs in the same
-- change (GetModelAction, GetAllModelsAction). Left as an inner join, a JAR-less model would be
-- invisible to merlin entirely -- which surfaces as "no such mission model" rather than "no JAR".
alter table merlin.mission_model
  add column model_type text not null default 'jar'
    constraint mission_model_type_check
      check (model_type in ('jar', 'declared')),
  add column external_identity_hash text,
  add column external_capabilities jsonb,
  alter column jar_id drop not null;

comment on column merlin.mission_model.model_type is e''
  'How this mission model is backed. "jar" is a Java JAR uploaded to PlanDev and classloaded to '
  'answer questions about the model. "declared" means its activity, resource and configuration types '
  'were declared to PlanDev directly and there is nothing behind them to run -- its results were '
  'produced elsewhere and imported.';

comment on column merlin.mission_model.jar_id is e''
  'An uploaded JAR file defining the mission model. Null for a model with no JAR, e.g. model_type '
  '"declared".';

-- Deliberately one column shared with the live-backend case rather than two that mean the same
-- thing. Both answer "were these results produced against the model PlanDev has, or a drifted one?";
-- they differ only in whether the digest was fetched from a running service or computed from a file.
comment on column merlin.mission_model.external_identity_hash is e''
  'A digest of this model''s declared type surface -- activity types with their parameters in '
  'declaration order, required parameters, computed-attribute schemas, resource schemas, '
  'configuration parameters and capabilities. It answers whether a set of results was produced '
  'against the model PlanDev holds or against a drifted one. Updating it bumps the model revision, '
  'which stamps results onto simulation_dataset.model_revision and invalidates the simulation cache, '
  'so writers must not write an unchanged value. Null for JAR models, whose stored bytes cannot '
  'drift from their own metadata.';

-- One jsonb rather than a boolean column per feature, and the reason is a constraint on the UI
-- rather than a taste for flexibility: plandev-ui must never contain a branch naming a particular
-- model or framework. A capability carrying its OWN reason lets the UI render "unavailable because
-- <text>" without knowing what produced the model. A boolean would push that sentence into the client.
comment on column merlin.mission_model.external_capabilities is e''
  'What PlanDev may DO with this model, as opposed to what the model is, keyed by capability name -- '
  'e.g. {"simulation":{"supported":false,"reason":"..."}}. Each value carries at least `supported`, '
  'plus whatever that capability needs; an unsupported one carries its own `reason`, which is the '
  'string the UI shows. An ABSENT capability means UNSUPPORTED. Null for JAR models, whose '
  'capabilities are not in question.';

call migrations.mark_migration_applied(38);
