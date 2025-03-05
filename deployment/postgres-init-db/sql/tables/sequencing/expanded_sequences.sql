create table sequencing.expanded_sequences (
  id integer generated always as identity,

  expansion_run_id integer not null,
  seq_id text not null,
  simulation_dataset_id int not null,
  expanded_sequence jsonb not null,

  created_at timestamptz not null default now(),

  constraint expanded_sequences_primary_key
    primary key (id),

  constraint expanded_sequences_to_expansion_run_id
    foreign key (expansion_run_id)
      references sequencing.expansion_run
      on delete cascade,

  constraint expanded_sequences_to_seq_id
    foreign key (seq_id, simulation_dataset_id)
      references sequencing.sequence (seq_id, simulation_dataset_id)
      on delete cascade,
  constraint expanded_sequences_to_sim_run
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade
);

comment on table sequencing.expanded_sequences is e''
  'A cache of sequences that have already been expanded.';
