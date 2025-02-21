create table sequencing.expanded_templates (
  id integer generated always as identity,

  filter_id int not null,
  simulation_dataset_id int not null,
  expanded_template jsonb not null,

  created_at timestamptz not null default now(),

  constraint expanded_template_primary_key
    primary key (id),

  constraint expanded_template_to_sim_run
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade,

  constraint expanded_template_to_filter
      foreign key (filter_id)
        references sequencing.sequence_filter
        on delete cascade
);

comment on table sequencing.expanded_templates is e''
  'A cache of sequences that have already been expanded.';
