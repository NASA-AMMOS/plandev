create table sequencing.sequence (
  seq_id text not null,
  simulation_dataset_id int not null,
  metadata jsonb,

  created_at timestamptz not null default now(),

  constraint sequence_primary_key
    primary key (seq_id, simulation_dataset_id),
  foreign key (simulation_dataset_id)
    references merlin.simulation_dataset
    on delete cascade
);
comment on table sequencing.sequence is e''
  'A sequence product';
comment on column sequencing.sequence.seq_id is e''
  'The FSW sequence specifier';
comment on column sequencing.sequence.simulation_dataset_id is e''
  'The simulation dataset id whose outputs are associated with this sequence';
