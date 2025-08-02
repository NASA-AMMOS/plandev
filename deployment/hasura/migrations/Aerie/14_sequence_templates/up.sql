-- introduce sequence templates
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


-- introduce sequence filters
create table sequencing.sequence_filter (
  id integer generated always as identity,
  filter jsonb not null default '{}'::jsonb,
  model_id integer not null,
  name text,

  constraint sequence_filter_primary_key primary key (id),

  constraint seq_filter_mission_model_exists foreign key (model_id)
    references merlin.mission_model
    on update cascade
    on delete cascade
);

comment on table sequencing.sequence_filter is e''
  'A table of sequence filters, which select the appropriate\n'
  'simulated activity instances for a given sequence.';

comment on column sequencing.sequence_filter.id is e''
  'The unique integer id for this sequence filter.';
comment on column sequencing.sequence_filter.filter is e''
  'The JSON-formatted filter over the simulated activities that\n'
  'is used to select the appropriate simulated activity instances for a given sequence.';
comment on column sequencing.sequence_filter.model_id is e''
  'The mission model that this filter applies to.\n'
  'This contextualizes the filter.';
comment on column sequencing.sequence_filter.name is e''
  'The name of the sequence filter.';


-- introduce a table to hold the result of expanded templates, in a separate table from expanded sequences because
--    this result is in text, not necessarily jsonb.
create table sequencing.expanded_templates (
  id integer generated always as identity,

  seq_id text not null,
  simulation_dataset_id int not null,
  expanded_template text not null,

  created_at timestamptz not null default now(),

  constraint expanded_template_primary_key
    primary key (id),

  constraint expanded_template_to_sim_run
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade,

  constraint expanded_template_to_sequence
    foreign key (seq_id, simulation_dataset_id)
      references sequencing.sequence
      on delete cascade
);

comment on table sequencing.expanded_templates is e''
  'A cache of sequences that have already been expanded.';

comment on column sequencing.expanded_templates.id is e''
  'The integer-generated unique id for an expanded template.';
comment on column sequencing.expanded_templates.seq_id is e''
  'The id of the sequence that this expansion correlates with.\n'
  'That sequence is what correlates this template with the activities it expands.';
comment on column sequencing.expanded_templates.simulation_dataset_id is e''
  'The id of the simulation that this expansion correlates with.\n'
  'This id tells us for what exact simulation run of a given plan (and therefore for what\n'
  'simulated activity entries) this expansion covers.';
comment on column sequencing.expanded_templates.expanded_template is e''
  'The content of the expanded template.';
comment on column sequencing.expanded_templates.created_at is e''
  'A temporal identifier that indicates when exactly this sequence was expanded.';


call migrations.mark_migration_applied('14');
