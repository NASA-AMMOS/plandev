create table ui.action_run (
  id integer generated always as identity,

  settings jsonb not null,
  parameters jsonb not null,
  logs text,
  error jsonb,
  results jsonb,
  status text not null default 'pending',

  action_definition_id integer not null,

  created_by text,
  created_at timestamptz not null default now(),

  constraint action_run_synthetic_key
    primary key (id),
  foreign key (action_definition_id)
    references ui.action_definition (id)
    on delete set null,
  foreign key (created_by)
    references permissions.users
    on update cascade
    on delete set null
);

create function ui.notify_action_run_inserted()
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
                    uf.path
             from ui.action_definition ad
             left join merlin.uploaded_file uf on uf.id = ad.action_file_id
             where ad.id = NEW.action_definition_id
           )
    select pg_notify('action_run_inserted', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

create trigger notify_action_run_inserted
  after insert on ui.action_run
  for each row
execute function ui.notify_action_run_inserted();
