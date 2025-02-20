create table sequencing.expanded_templates (
  id integer generated always as identity,

  template_expansion_run_id integer not null,
  seq_id text not null,
  simulation_dataset_id int not null,
  expanded_sequence jsonb not null, -- MAY WANT TO MAKE THIS A STRING INSTEAD. LEAVE SEQJSON/CSTOLJSON? AS EPHEMERAL. BUT ALL OUR CONVERSIONS ARE STRING-STRING, AND SHOULD BE MAINTAINED AS SUCH

  created_at timestamptz not null default now(),

  constraint expanded_template_primary_key
    primary key (id),

  constraint expanded_template_to_template_expansion_run_id
    foreign key (template_expansion_run_id)
      references sequencing.template_expansion_run
      on delete cascade,

  constraint expanded_template_to_seq_id
    foreign key (seq_id, simulation_dataset_id)
      references sequencing.sequence (seq_id, simulation_dataset_id)
      on delete cascade,
  constraint expanded_template_to_sim_run
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade
);

comment on table sequencing.expanded_templates is e''
  'A cache of sequences that have already been expanded.';
