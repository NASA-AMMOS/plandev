create table merlin.constraint_results (
  id integer not null generated always as identity,

  -- inputs
  constraint_id integer not null,
  constraint_revision integer not null,
  simulation_dataset_id integer not null,
  arguments jsonb not null,

  -- outputs
  results jsonb not null default '{}',
  errors jsonb not null default '{}',

  constraint constraint_results_key
    primary key (id),
  constraint constraint_results_to_constraint_definition
    foreign key (constraint_id, constraint_revision)
      references merlin.constraint_definition
      on delete cascade,
  constraint constraint_results_to_simulation_dataset
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade
);

create index constraint_results_simulation_dataset_id_index
  on merlin.constraint_results (simulation_dataset_id);

comment on table merlin.constraint_results is e''
  'The output of running a single constraint. Results are cached to avoid unnecessary execution.';

comment on column merlin.constraint_results.id is e''
  'The generated identifier for this results.';
comment on column merlin.constraint_results.constraint_id is e''
  'The constraint that was checked.';
comment on column merlin.constraint_results.constraint_revision is e''
  'The version of the constraint definition that was checked.';
comment on column merlin.constraint_results.simulation_dataset_id is e''
  'The simulation dataset the constraint was checked against.';
comment on column merlin.constraint_results.arguments is e''
  'The arguments provided to the constraint when checked.';
comment on column merlin.constraint_results.results is e''
  'Results that were computed during the constraint check.';
comment on column merlin.constraint_results.errors is e''
  'Errors that occurred while checking the constraint.';
