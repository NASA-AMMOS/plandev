create table actions.action_definition_version (
  action_definition_id integer not null,
  revision integer not null default 0,

  action_file_id integer not null,
  parameter_schema jsonb not null default '{}'::jsonb,
  settings_schema jsonb not null default '{}'::jsonb,
  author text,
  archived boolean not null default false,
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

comment on table actions.action_definition_version is e''
  'An immutable revision of an action definition''s code and schemas.';
comment on column actions.action_definition_version.action_definition_id is e''
  'The ID of the parent action definition.';
comment on column actions.action_definition_version.revision is e''
  'The auto-incremented revision number within this action definition.';
comment on column actions.action_definition_version.action_file_id is e''
  'The ID of the uploaded action file for this version.';
comment on column actions.action_definition_version.parameter_schema is e''
  'The JSON schema representing the action''s parameters for this version.';
comment on column actions.action_definition_version.settings_schema is e''
  'The JSON schema representing the action''s settings for this version.';
comment on column actions.action_definition_version.author is e''
  'The user who created this version.';
comment on column actions.action_definition_version.archived is e''
  'Whether this version is archived (hidden from default version lists).';
comment on column actions.action_definition_version.created_at is e''
  'When this version was created.';

-- Auto-increment revision per action_definition_id
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

-- Notify action server when a new version is uploaded
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
