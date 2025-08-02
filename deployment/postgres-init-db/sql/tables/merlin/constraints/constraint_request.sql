create table merlin.constraint_request (
  id integer not null generated always as identity
    primary key,
  plan_id integer not null,
  simulation_dataset_id integer not null, -- "not null", as we reject rqs if there aren't up-to-date sim results
                                          -- (or if the provided sim dataset id is invalid)
  force_rerun boolean,

  -- Additional Metadata
  requested_by text,
  requested_at timestamptz not null default now(),

  constraint constraint_request_plan
    foreign key (plan_id)
      references merlin.plan
      on delete cascade,
  constraint constraint_request_simulation_dataset
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade,
  constraint constraint_request_requested_by
    foreign key (requested_by)
      references permissions.users
      on update cascade
      on delete set null
);

comment on table merlin.constraint_request is e''
 'A record of the inputs to an executed constraint request.';
comment on column merlin.constraint_request.id is e''
 'The generated identifier for this request.';
comment on column merlin.constraint_request.plan_id is e''
 'The plan used during this request.';
comment on column merlin.constraint_request.simulation_dataset_id is e''
 'The simulation results used during this request.';
comment on column merlin.constraint_request.force_rerun is e''
 'Whether this request specified the "force" flag.';
comment on column merlin.constraint_request.requested_by is e''
 'The user who made the request.';
comment on column merlin.constraint_request.requested_at is e''
 'The time at which the request was made.';
