create type scheduler.goal_type as enum ('EDSL', 'JAR');

alter table scheduler.scheduling_goal_definition
  add column type scheduler.goal_type not null default 'EDSL',
  add column uploaded_jar_id integer,
  add column parameter_schema jsonb,

  alter column definition drop not null,

  add constraint scheduling_procedure_has_uploaded_jar
    foreign key (uploaded_jar_id)
    references merlin.uploaded_file
    on update cascade
    on delete restrict,

  add constraint check_goal_definition_type_consistency
    check (
    (type = 'EDSL' and definition is not null and uploaded_jar_id is null)
    or
    (type = 'JAR' and uploaded_jar_id is not null and definition is null)
    );

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

alter table scheduler.scheduling_specification_goals
  add column arguments jsonb not null default '{}'::jsonb;

comment on column scheduler.scheduling_specification_goals.arguments is e''
  'The arguments that will be passed to this goal when invoked.'
  'Follows scheduler.scheduling_goal_definition.parameter_schema.'
  'Only valid for procedural goals.';

alter table scheduler.scheduling_goal_analysis
  add column arguments jsonb not null default '{}'::jsonb;

comment on column scheduler.scheduling_goal_analysis.arguments is e''
  'The "as run" arguments passed to this goal during the scheduling run.'
  'Follows scheduler.scheduling_goal_definition.parameter_schema.';

call migrations.mark_migration_applied('10');
