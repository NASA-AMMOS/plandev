create table sequencing.sequence_template (
  id integer generated always as identity,
  name text not null,

  model_id integer null,
  parcel_id integer null,
  template_definition text not null,
  activity_type text not null,
  language text not null,
  owner text,

  constraint sequence_template_pkey primary key (id),
  constraint seq_template_mission_model_exists foreign key (model_id)
    references merlin.mission_model (id)
    on update cascade
    on delete set null,
  constraint seq_template_parcel_exists foreign key (parcel_id)
    references sequencing.parcel (id)
    on update cascade
    on delete set null,

  constraint only_one_template_per_model_activity_type
    unique (model_id, activity_type)
);

comment on table sequencing.sequence_template is e''
  'A table of sequence templates for given activity types.';

comment on column sequencing.sequence_template.id is e''
  'The unique integer id for this sequence template.';
comment on column sequencing.sequence_template.name is e''
  'The user-provided name for this template.';
comment on column sequencing.sequence_template.model_id is e''
  'The mission model id that this template applies to.\n'
  'This id is used in correlating this template with a real activity type.';
comment on column sequencing.sequence_template.parcel_id is e''
  'The parcel that this template uses.\n'
  'This id is used to define available commands for this template.';
comment on column sequencing.sequence_template.template_definition is e''
  'The actual, text definition for this template.\n'
  'Text should be formatted as Handlebars/Mustache-compliant text.';
comment on column sequencing.sequence_template.activity_type is e''
  'The activity type that this sequence template applies to.';
comment on column sequencing.sequence_template.language is e''
  'The language (STOL, SeqN) that this sequence template is written in.';
comment on column sequencing.sequence_template.owner is e''
  'The user that created this sequence template.';
