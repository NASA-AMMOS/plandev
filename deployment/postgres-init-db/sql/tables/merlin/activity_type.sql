create table merlin.activity_type (
  model_id integer not null,
  name text not null,
  parameters merlin.parameter_set not null,
  required_parameters merlin.required_parameter_set not null,
  computed_attributes_value_schema jsonb,
  subsystem integer references tags.tags
    on update cascade
    on delete restrict,
  description text,

  constraint activity_type_pkey
    primary key (model_id, name),
  constraint activity_type_mission_model_exists
    foreign key (model_id)
    references merlin.mission_model
    on delete cascade
);

comment on table merlin.activity_type is e''
  'A description of a parametric activity type supported by the associated mission model.';

comment on column merlin.activity_type.name is e''
  'The name of this activity type, unique within a mission model.';
comment on column merlin.activity_type.model_id is e''
  'The model defining this activity type.';
comment on column merlin.activity_type.parameters is e''
  'The set of parameters accepted by this activity type.';
comment on column merlin.activity_type.required_parameters is e''
  'A description of which parameters are required to be provided to instantiate this activity type';
comment on column merlin.activity_type.computed_attributes_value_schema is e''
  'The type of value returned by the effect model of this activity type';
comment on column merlin.activity_type.subsystem is e''
  'The subsystem this activity type belongs to.';
comment on column merlin.activity_type.description is e''
  'The description of this activity type.';
