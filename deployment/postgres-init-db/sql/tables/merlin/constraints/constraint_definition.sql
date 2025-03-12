create table merlin.constraint_definition(
  constraint_id integer not null,
  revision integer not null default 0,

  type merlin.constraint_type not null default 'EDSL',
  definition text,
  uploaded_jar_id integer,
  parameter_schema jsonb,
  author text,
  created_at timestamptz not null default now(),

  constraint constraint_definition_pkey
    primary key (constraint_id, revision),
  constraint constraint_definition_constraint_exists
    foreign key (constraint_id)
    references merlin.constraint_metadata
    on update cascade
    on delete cascade,
  constraint constraint_definition_author_exists
    foreign key (author)
    references permissions.users
    on update cascade
    on delete set null,
  constraint constraint_procedure_has_uploaded_jar
    foreign key (uploaded_jar_id)
      references merlin.uploaded_file
      on update cascade
      on delete restrict,
  constraint check_constraint_definition_type_consistency
    check (
      (type = 'EDSL' and definition is not null and uploaded_jar_id is null)
      or
      (type = 'JAR' and uploaded_jar_id is not null and definition is null)
    )
);

comment on table merlin.constraint_definition is e''
  'The specific revisions of a constraint''s definition';
comment on column merlin.constraint_definition.revision is e''
  'An identifier of this definition.';
comment on column merlin.constraint_definition.type is e''
  'The type of this definition, "EDSL" or "JAR".';
comment on column merlin.constraint_definition.definition is e''
  'An executable expression in the Merlin constraint language.'
  'Should be non-null when type is EDSL';
comment on column merlin.constraint_definition.uploaded_jar_id is e''
  'The foreign key to the uploaded_file entry containing the procedure jar'
  'Should be non-null when type is JAR';
comment on column merlin.constraint_definition.parameter_schema is e''
  'The schema for parameters that can be passed to constraint instances using this definition.'
  'Similar schema to parameter_set''s in Merlin.';
comment on column merlin.constraint_definition.author is e''
  'The user who authored this revision.';
comment on column merlin.constraint_definition.created_at is e''
  'When this revision was created.';

create function merlin.constraint_definition_set_revision()
returns trigger
volatile
language plpgsql as $$
declare
  max_revision integer;
begin
  -- Grab the current max value of revision, or -1, if this is the first revision
  select coalesce((select revision
  from merlin.constraint_definition
  where constraint_id = new.constraint_id
  order by revision desc
  limit 1), -1)
  into max_revision;

  new.revision = max_revision + 1;
  return new;
end
$$;

create trigger constraint_definition_set_revision
  before insert on merlin.constraint_definition
  for each row
  execute function merlin.constraint_definition_set_revision();
