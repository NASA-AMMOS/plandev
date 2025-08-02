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
