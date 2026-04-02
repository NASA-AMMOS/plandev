-- 1. Create action_definition_version table
create table actions.action_definition_version (
  action_definition_id integer not null,
  revision integer not null default 0,

  action_file_id integer not null,
  parameter_schema jsonb not null default '{}'::jsonb,
  settings_schema jsonb not null default '{}'::jsonb,
  archived boolean not null default false,
  author text,
  created_at timestamptz not null default now(),

  constraint action_definition_version_pkey
    primary key (action_definition_id, revision),
  constraint action_definition_version_definition_exists
    foreign key (action_definition_id)
    references actions.action_definition (id)
    on update cascade
    on delete cascade,
  constraint action_definition_version_author_exists
    foreign key (author)
    references permissions.users
    on update cascade
    on delete set null,
  constraint action_definition_version_references_action_file
    foreign key (action_file_id)
    references merlin.uploaded_file
    on update cascade
    on delete restrict
);

-- 2. Auto-increment revision trigger (same pattern as constraint_definition)
create function actions.action_definition_version_set_revision()
returns trigger
volatile
language plpgsql as $$
declare
  max_revision integer;
begin
  select coalesce((select revision
  from actions.action_definition_version
  where action_definition_id = new.action_definition_id
  order by revision desc
  limit 1), -1)
  into max_revision;

  new.revision = max_revision + 1;
  return new;
end
$$;

create trigger action_definition_version_set_revision
  before insert on actions.action_definition_version
  for each row
  execute function actions.action_definition_version_set_revision();

-- 3. Migrate existing data: create version 0 for each existing action_definition
insert into actions.action_definition_version (action_definition_id, action_file_id, parameter_schema, settings_schema, author)
select id, action_file_id, parameter_schema, settings_schema, owner
from actions.action_definition;

-- 4. Add archived column to action_definition & update description
alter table actions.action_definition
  add column archived boolean not null default false;

comment on table actions.action_definition is e''
  'Unversioned user-provided information about a SeqDev action.';

-- 5. Add action_definition_revision to action_run backfill existing runs to 0
--   no default - future inserts w/o explicit revision are auto-set before insert by action_run_set_default_revision
alter table actions.action_run
  add column action_definition_revision integer;

update actions.action_run set action_definition_revision = 0;

alter table actions.action_run
  alter column action_definition_revision set not null;

-- 6. Move notify_action_definition_inserted trigger to version table
drop trigger notify_action_definition_inserted on actions.action_definition;
drop function actions.notify_action_definition_inserted();

create function actions.notify_action_definition_version_inserted()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform (
    with payload(action_definition_id, revision, action_file_path) as
           (
             select NEW.action_definition_id,
                    NEW.revision,
                    encode(uf.path, 'escape') as path
             from merlin.uploaded_file uf
             where uf.id = NEW.action_file_id
           )
    select pg_notify('action_definition_version_inserted', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

create trigger notify_action_definition_version_inserted
  after insert on actions.action_definition_version
  for each row
execute function actions.notify_action_definition_version_inserted();

-- 7. Update notify_action_run_inserted to resolve file from version table
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
                 action_definition_revision,
                 has_secrets,
                 workspace_id,
                 action_file_path) as
           (
             select NEW.id,
                    NEW.settings,
                    NEW.parameters,
                    NEW.action_definition_id,
                    NEW.action_definition_revision,
                    NEW.has_secrets,
                    ad.workspace_id,
                    encode(uf.path, 'escape') as path
             from actions.action_definition ad
                    left join actions.action_definition_version adv
                      on adv.action_definition_id = ad.id
                      and adv.revision = NEW.action_definition_revision
                    left join merlin.uploaded_file uf on uf.id = adv.action_file_id
                    where ad.id = NEW.action_definition_id
           )
    select pg_notify('action_run_inserted', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

-- 8. Auto-populate action_definition_revision on run insert (defaults to latest)
create function actions.action_run_set_default_revision()
returns trigger
volatile
language plpgsql as $$
begin
  if new.action_definition_revision is null then
    select coalesce(
      (select revision from actions.action_definition_version
       where action_definition_id = new.action_definition_id
       order by revision desc limit 1),
      0
    ) into new.action_definition_revision;
  end if;
  return new;
end
$$;

create trigger action_run_set_default_revision
  before insert on actions.action_run
  for each row
  execute function actions.action_run_set_default_revision();

-- 9. Drop old columns from action_definition
alter table actions.action_definition
  drop column action_file_id,
  drop column parameter_schema,
  drop column settings_schema;

call migrations.mark_migration_applied(31);
