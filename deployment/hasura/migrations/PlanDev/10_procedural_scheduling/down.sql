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

-- restore goal_analysis_* tables

-- drop new PKs for created / satisfying activities
-- and add old columns back so we can populate and re-PK
alter table scheduler.scheduling_goal_analysis_satisfying_activities
  drop constraint satisfying_activities_primary_key,
  drop constraint satisfying_activities_references_scheduling_goal_analysis,

  -- temp set as nullable so we can insert, made explictly not null below
  add column goal_id integer null,
  add column goal_revision integer null;

comment on column scheduler.scheduling_goal_analysis_satisfying_activities.goal_id is e''
  'The associated goal ID.';
comment on column scheduler.scheduling_goal_analysis_satisfying_activities.goal_revision is e''
  'The associated version of the goal definition used.';

alter table scheduler.scheduling_goal_analysis_created_activities
  drop constraint created_activities_primary_key,
  drop constraint created_activities_references_scheduling_goal_analysis,

  add column goal_id integer null,
  add column goal_revision integer null;

comment on column scheduler.scheduling_goal_analysis_created_activities.goal_id is e''
  'The associated goal ID.';
comment on column scheduler.scheduling_goal_analysis_created_activities.goal_revision is e''
  'The associated version of the goal definition used.';

update scheduler.scheduling_goal_analysis_satisfying_activities as sa
set goal_id = ga.goal_id, goal_revision = ga.goal_revision
from scheduler.scheduling_goal_analysis ga
where (sa.analysis_id, sa.goal_invocation_id) = (ga.analysis_id, ga.goal_invocation_id);

update scheduler.scheduling_goal_analysis_created_activities as ca
set goal_id = ga.goal_id, goal_revision = ga.goal_revision
from scheduler.scheduling_goal_analysis ga
where (ca.analysis_id, ca.goal_invocation_id) = (ga.analysis_id, ga.goal_invocation_id);

alter table scheduler.scheduling_goal_analysis_satisfying_activities
  -- explictly restore non-nullability before PKing
  alter column goal_id set not null,
  alter column goal_revision set not null,
  add constraint satisfying_activities_primary_key
    primary key (analysis_id, goal_id, goal_revision, activity_id),

  add constraint satisfying_activities_references_scheduling_goal
    foreign key (goal_id, goal_revision)
      references scheduler.scheduling_goal_definition
      on update cascade
      on delete cascade,

  drop column goal_invocation_id;

alter table scheduler.scheduling_goal_analysis_created_activities
  -- explictly restore non-nullability before PKing
  alter column goal_id set not null,
  alter column goal_revision set not null,

  add constraint created_activities_primary_key
    primary key (analysis_id, goal_id, goal_revision, activity_id),

  add constraint created_activities_references_scheduling_goal
    foreign key (goal_id, goal_revision)
      references scheduler.scheduling_goal_definition
      on update cascade
      on delete cascade,

  drop column goal_invocation_id;

alter table scheduler.scheduling_goal_analysis
  drop constraint scheduling_goal_analysis_primary_key;

-- delete all but the first goal invocation from analysis table if there nonuniqueness
-- between the composite key that will become the new (old) PK
delete
from scheduler.scheduling_goal_analysis
where goal_invocation_id not in (select min(goal_invocation_id)
                                 from scheduler.scheduling_goal_analysis
                                 group by analysis_id, goal_id, goal_revision);

alter table scheduler.scheduling_goal_analysis
  add constraint scheduling_goal_analysis_primary_key
    primary key (analysis_id, goal_id, goal_revision),

  drop column goal_invocation_id;

comment on table scheduler.scheduling_goal_analysis is e''
  'The analysis of single goal from a scheduling run.';

-- restore scheduling_specification_goals
-- delete data for new PK
-- i.e. find where (spec_id, goal_id) is not unique
-- and delete all entries except for the one with the lowest goal_invocation_id
-- so that (spec_id, goal_id) can be the new PK
-- delete from scheduler.scheduling_specification_goals a
delete
from scheduler.scheduling_specification_goals
where goal_invocation_id not in (select min(goal_invocation_id)
                                 from scheduler.scheduling_specification_goals
                                 group by specification_id, goal_id);

alter table scheduler.scheduling_specification_goals
  drop constraint scheduling_specification_goals_primary_key,
  add constraint scheduling_specification_goals_primary_key
    primary key (specification_id, goal_id),

  drop column goal_invocation_id;

comment on column scheduler.scheduling_specification_goals.specification_id is e''
  'The plan scheduling specification this goal is on. Half of the primary key.';

comment on column scheduler.scheduling_specification_goals.goal_id is e''
  'The id of a specific goal in the specification. Half of the primary key.';

call migrations.mark_migration_rolled_back('10');
