-- delete all scheduling procedures from specs
-- (on delete is restricted)
delete from scheduler.scheduling_specification_goals sg
  using scheduler.scheduling_goal_definition gd
  where gd.goal_id = sg.goal_id
  and gd.type = 'JAR'::scheduler.goal_type;

-- delete all scheduling procedure definitions
delete from scheduler.scheduling_goal_metadata gm
  using scheduler.scheduling_goal_definition gd
  where gm.id = gd.goal_id
  and gd.type = 'JAR'::scheduler.goal_type;

alter table scheduler.scheduling_goal_analysis
  drop column arguments;

alter table scheduler.scheduling_specification_goals
  drop column arguments;

alter table scheduler.scheduling_goal_definition
  drop constraint check_goal_definition_type_consistency,
  drop constraint scheduling_procedure_has_uploaded_jar,

  drop column type,
  drop column uploaded_jar_id,
  drop column parameter_schema,

  alter column definition set not null;

comment on column scheduler.scheduling_goal_definition.definition is e''
  'An executable expression in the Merlin scheduling language.';

drop type scheduler.goal_type;

call migrations.mark_migration_rolled_back('10');
