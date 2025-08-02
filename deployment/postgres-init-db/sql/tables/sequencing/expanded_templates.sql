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
