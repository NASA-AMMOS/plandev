create table merlin.mission_model (
  id integer generated always as identity,
  revision integer not null default 0,

  mission text not null,
  name text not null,
  version text not null,
  description text not null default '',
  default_view_id integer default null,

  owner text,
  jar_id integer,
  model_type text not null default 'jar',
  external_backend_url text,

  created_at timestamptz not null default now(),

  constraint mission_model_synthetic_key
    primary key (id),
  constraint mission_model_natural_key
    unique (mission, name, version),
  constraint mission_model_type_check
    check (model_type = 'jar' or model_type = 'external'),
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
  'An uploaded JAR file defining the mission model. Null for non-JAR (e.g. "external") models.';
comment on column merlin.mission_model.model_type is e''
  'The kind of backend that defines this mission model.\n'
  '"jar" is a Java JAR uploaded to Aerie; "external" is a foreign model backend served outside Aerie.';
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
