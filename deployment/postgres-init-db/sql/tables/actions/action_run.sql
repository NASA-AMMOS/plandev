create table actions.action_run (
  id integer generated always as identity,

  settings jsonb not null,
  parameters jsonb not null,
  logs text,
  error jsonb,
  results jsonb,
  status actions.action_run_status not null default 'pending',

  action_definition_id integer not null,

  created_by text,
  created_at timestamptz not null default now(),

  constraint action_run_synthetic_key
    primary key (id),
  foreign key (action_definition_id)
    references actions.action_definition (id)
    on delete set null,
  foreign key (created_by)
    references permissions.users
    on update cascade
    on delete set null
);

comment on table actions.action_run is e''
  'The record of a single run of an action.';
comment on column actions.action_run.settings is e''
  'The supplied settings for the run of the action.';
comment on column actions.action_run.parameters is e''
  'The supplied parameters for the run of the action.';
comment on column actions.action_run.logs is e''
  'The logs produced by the action run.';
comment on column actions.action_run.error is e''
  'The error produced by the action run.';
comment on column actions.action_run.results is e''
  'The results produced by the action run.';
comment on column actions.action_run.status is e''
  'The status of the action run. pending -> in-progress -> failed or complete';
comment on column actions.action_run.action_definition_id is e''
  'The ID of the definition of the action.';

create function actions.notify_action_run_inserted()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform (
    with payload(settings,
                 parameters,
                 action_definition_id,
                 workspace_id,
                 action_file_path) as
           (
             select NEW.settings,
                    NEW.parameters,
                    NEW.action_definition_id,
                    ad.workspace_id,
                    uf.name
             from actions.action_definition ad
                    left join merlin.uploaded_file uf on uf.id = ad.action_file_id
                    where ad.id = NEW.action_definition_id
           )
    select pg_notify('action_run_inserted', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

create trigger notify_action_run_inserted
  after insert on actions.action_run
  for each row
execute function actions.notify_action_run_inserted();
