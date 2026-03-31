-- Drop default revision trigger on action_run
drop trigger action_run_set_default_revision on actions.action_run;
drop function actions.action_run_set_default_revision();

-- Restore columns on action_definition
alter table actions.action_definition
  add column action_file_id integer,
  add column parameter_schema jsonb not null default '{}'::jsonb,
  add column settings_schema jsonb not null default '{}'::jsonb;

-- Populate from latest version
update actions.action_definition ad
set action_file_id = v.action_file_id,
    parameter_schema = v.parameter_schema,
    settings_schema = v.settings_schema
from (
  select def.revision, def.action_definition_id, def.action_file_id, def.parameter_schema, def.settings_schema
  from actions.action_definition_version def
  where def.action_definition_id = ad.id
  order by def.revision desc
  limit 1
) v
where ad.id = v.action_definition_id;

-- Make action_file_id not null
alter table actions.action_definition
  alter column action_file_id set not null;

-- Add FK back
alter table actions.action_definition
  add constraint action_definition_references_action_file
    foreign key (action_file_id)
    references merlin.uploaded_file
    on update cascade
    on delete restrict;

-- Drop revision from action_run
alter table actions.action_run drop column action_definition_revision;

-- Drop archived
alter table actions.action_definition drop column archived;

-- Restore original notification triggers
drop trigger notify_action_definition_version_inserted on actions.action_definition_version;
drop function actions.notify_action_definition_version_inserted();

create function actions.notify_action_definition_inserted()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform (
    with payload(action_definition_id, action_file_path) as
           (
             select NEW.id,
                    encode(uf.path, 'escape') as path
             from merlin.uploaded_file uf
             where uf.id = NEW.action_file_id
           )
    select pg_notify('action_definition_inserted', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

create trigger notify_action_definition_inserted
  after insert on actions.action_definition
  for each row
execute function actions.notify_action_definition_inserted();

-- Restore original run trigger
create or replace function actions.notify_action_run_inserted()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform (
    with payload(action_run_id,
                 settings,
                 parameters,
                 action_definition_id,
                 has_secrets,
                 workspace_id,
                 action_file_path) as
           (
             select NEW.id,
                    NEW.settings,
                    NEW.parameters,
                    NEW.action_definition_id,
                    NEW.has_secrets,
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

-- Drop version table and its trigger function
drop table actions.action_definition_version;
drop function actions.action_definition_version_set_revision();

call migrations.mark_migration_rolled_back(31);
