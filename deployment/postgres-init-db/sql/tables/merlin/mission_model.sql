create table merlin.mission_model (
  id integer generated always as identity,
  revision integer not null default 0,

  mission text not null,
  name text not null,
  version text not null,
  description text not null default '',
  default_view_id integer default null,

  owner text,
  -- Nullable: a model whose types were declared to PlanDev rather than compiled has no JAR. The FK
  -- below survives -- a null foreign key references nothing and is permitted.
  jar_id integer,

  -- How this model is backed. Written as a list rather than as equality tests because a third kind
  -- (a live external backend) is already designed, and widening a list is a one-token change.
  model_type text not null default 'jar'
    constraint mission_model_type_check
      check (model_type in ('jar', 'declared')),
  external_identity_hash text,
  external_capabilities jsonb,

  created_at timestamptz not null default now(),

  constraint mission_model_synthetic_key
    primary key (id),
  constraint mission_model_natural_key
    unique (mission, name, version),
  constraint mission_model_references_jar
    foreign key (jar_id)
    references merlin.uploaded_file
    on update cascade
    on delete restrict,
  constraint mission_model_owner_exists
    foreign key (owner) references permissions.users
    on update cascade
    on delete set null,
  foreign key (default_view_id)
    references ui.view
    on delete set null
);

comment on table merlin.mission_model is e''
  'A Merlin simulation model for a mission.';

comment on column merlin.mission_model.id is e''
  'The synthetic identifier for this mission model.';
comment on column merlin.mission_model.revision is e''
  'A monotonic clock that ticks for every change to this mission model.';
comment on column merlin.mission_model.mission is e''
  'A human-meaningful identifier for the mission described by this model.';
comment on column merlin.mission_model.name is e''
  'A human-meaningful model name.';
comment on column merlin.mission_model.version is e''
  'A human-meaningful version qualifier.';
comment on column merlin.mission_model.owner is e''
  'A human-meaningful identifier for the user responsible for this model.';
comment on column merlin.mission_model.jar_id is e''
  'An uploaded JAR file defining the mission model. Null for a model with no JAR, e.g. model_type '
  '"declared".';
comment on column merlin.mission_model.model_type is e''
  'How this mission model is backed. "jar" is a Java JAR uploaded to PlanDev and classloaded to '
  'answer questions about the model. "declared" means its activity, resource and configuration types '
  'were declared to PlanDev directly and there is nothing behind them to run -- its results were '
  'produced elsewhere and imported.';
comment on column merlin.mission_model.external_identity_hash is e''
  'A digest of this model''s declared type surface -- activity types with their parameters in '
  'declaration order, required parameters, computed-attribute schemas, resource schemas, '
  'configuration parameters and capabilities. It answers whether a set of results was produced '
  'against the model PlanDev holds or against a drifted one. Updating it bumps the model revision, '
  'which stamps results onto simulation_dataset.model_revision and invalidates the simulation cache, '
  'so writers must not write an unchanged value. Null for JAR models, whose stored bytes cannot '
  'drift from their own metadata.';
comment on column merlin.mission_model.external_capabilities is e''
  'What PlanDev may DO with this model, as opposed to what the model is, keyed by capability name -- '
  'e.g. {"simulation":{"supported":false,"reason":"..."}}. Each value carries at least `supported`, '
  'plus whatever that capability needs; an unsupported one carries its own `reason`, which is the '
  'string the UI shows. An ABSENT capability means UNSUPPORTED. Null for JAR models, whose '
  'capabilities are not in question.';
comment on column merlin.mission_model.created_at is e''
  'The time this mission model was uploaded into Aerie.';
comment on column merlin.mission_model.description is e''
  'A human-meaningful description of the mission model.';
comment on column merlin.mission_model.default_view_id is e''
  'The ID of an optional default view for the mission model.';

create trigger increment_revision_mission_model_update
before update on merlin.mission_model
for each row
when (pg_trigger_depth() < 1)
execute function util_functions.increment_revision_update();

create function merlin.increment_revision_mission_model_jar_update()
returns trigger
security definer
language plpgsql as $$begin
  update merlin.mission_model
  set revision = revision + 1
  where jar_id = new.id
    or jar_id = old.id;

  return new;
end$$;

create trigger increment_revision_mission_model_jar_update_trigger
after update on merlin.uploaded_file
for each row
execute function merlin.increment_revision_mission_model_jar_update();
