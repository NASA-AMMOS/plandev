create table scheduler.scheduling_goal_definition(
  goal_id integer not null,
  revision integer not null default 0,

  type scheduler.goal_type not null default 'EDSL',
  definition text,
  uploaded_jar_id integer,
  parameter_schema jsonb,
  author text,
  created_at timestamptz not null default now(),

  constraint scheduling_goal_definition_pkey
    primary key (goal_id, revision),
  constraint scheduling_goal_definition_goal_exists
    foreign key (goal_id)
    references scheduler.scheduling_goal_metadata
    on update cascade
    on delete cascade,
  constraint scheduling_procedure_has_uploaded_jar
    foreign key (uploaded_jar_id)
      references merlin.uploaded_file
      on update cascade
      on delete restrict,
  constraint goal_definition_author_exists
    foreign key (author)
    references permissions.users
    on update cascade
    on delete set null,
  constraint check_goal_definition_type_consistency
    check (
      (type = 'EDSL' and definition is not null and uploaded_jar_id is null)
        or
      (type = 'JAR' and uploaded_jar_id is not null and definition is null)
    )
);

comment on table scheduler.scheduling_goal_definition is e''
  'The specific revisions of a scheduling goal''s definition';
comment on column scheduler.scheduling_goal_definition.revision is e''
  'An identifier of this definition.';
comment on column scheduler.scheduling_goal_definition.type is e''
  'The type of this goal definition, "EDSL" or "JAR".';
comment on column scheduler.scheduling_goal_definition.definition is e''
  'An executable expression in the Merlin scheduling language.'
  'Should be non-null when type is EDSL';
comment on column scheduler.scheduling_goal_definition.uploaded_jar_id is e''
  'The foreign key to the uploaded_file entry containing the procedure jar'
  'Should be non-null when type is JAR';
comment on column scheduler.scheduling_goal_definition.parameter_schema is e''
  'The schema for parameters that can be passed to goal instances using this definition.'
  'Similar schema to parameter_set''s in Merlin.';
comment on column scheduler.scheduling_goal_definition.author is e''
  'The user who authored this revision.';
comment on column scheduler.scheduling_goal_definition.created_at is e''
  'When this revision was created.';

create function scheduler.scheduling_goal_definition_set_revision()
returns trigger
volatile
language plpgsql as $$
declare
  max_revision integer;
begin
  -- Grab the current max value of revision, or -1, if this is the first revision
  select coalesce((select revision
  from scheduler.scheduling_goal_definition
  where goal_id = new.goal_id
  order by revision desc
  limit 1), -1)
  into max_revision;

  new.revision = max_revision + 1;
  return new;
end
$$;

create trigger scheduling_goal_definition_set_revision
  before insert on scheduler.scheduling_goal_definition
  for each row
  execute function scheduler.scheduling_goal_definition_set_revision();
