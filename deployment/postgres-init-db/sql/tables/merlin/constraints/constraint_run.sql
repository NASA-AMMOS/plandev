create table merlin.constraint_run(
  request_id integer not null,
  constraint_invocation_id integer not null,
  constraint_results_id integer not null,
  priority integer not null,

  constraint constraint_run_pkey
    primary key (request_id, constraint_invocation_id, constraint_results_id),
  constraint constraint_run_request foreign key (request_id)
    references merlin.constraint_request
    on delete cascade,
  constraint constraint_run_invocation foreign key (constraint_invocation_id)
    references merlin.constraint_specification
    on delete cascade,
  constraint constraint_run_results foreign key (constraint_results_id)
    references merlin.constraint_results
    on delete restrict
);

comment on table merlin.constraint_run is e''
  'A join table connecting each constraint run during a request to its result.';

comment on column merlin.constraint_run.request_id is e''
  'The constraint request during which this constraint was checked.';
comment on column merlin.constraint_run.constraint_invocation_id is e''
  'The constraint that was checked.';
comment on column merlin.constraint_run.constraint_results_id is e''
  'The output of checking the constraint.';
comment on column merlin.constraint_run.priority is e''
  'The priority the constraint had when checked.';
