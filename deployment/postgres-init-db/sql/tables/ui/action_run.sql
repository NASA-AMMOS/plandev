create table ui.action_run (
  id integer generated always as identity,

  settings jsonb not null,
  parameters jsonb not null,
  logs text not null default '',
  errors jsonb not null,
  results jsonb not null,
  status text not null,

  action_definition_id integer not null,

  ran_by text,
  ran_at timestamptz not null default now(),

  constraint action_run_synthetic_key
    primary key (id),
  foreign key (action_definition_id)
    references ui.action_run (id)
    on delete set null,
  foreign key (ran_by)
    references permissions.users
    on update cascade
    on delete set null
);
