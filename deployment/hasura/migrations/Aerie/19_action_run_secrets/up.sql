alter table actions.action_run
  add column secrets boolean not null default false;

comment on column actions.action_run.secrets is e''
  'A flag that is set to true if the run has secrets, otherwise false.';

drop trigger notify_action_run_inserted;
drop function actions.notify_action_run_inserted;

create function actions.notify_action_run_inserted()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform (
    with payload(action_run_id,
                 settings,
                 parameters,
                 action_definition_id,
                 secrets,
                 workspace_id,
                 action_file_path) as
           (
             select NEW.id,
                    NEW.settings,
                    NEW.parameters,
                    NEW.action_definition_id,
                    NEW.secrets,
                    ad.workspace_id,
                    encode(uf.path, 'escape') as path
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

call migrations.mark_migration_applied('19');
