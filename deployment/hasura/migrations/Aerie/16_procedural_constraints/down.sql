/*********************
  Constraint Requests
 *********************/

-- Remove the new request tables:
drop table merlin.constraint_run;
drop table merlin.constraint_results;
drop table merlin.constraint_request;

create table merlin.constraint_run (
  constraint_id integer not null,
  constraint_revision integer not null,
  simulation_dataset_id integer not null,

  results jsonb not null default '{}',

  -- Additional Metadata
  requested_by text,
  requested_at timestamptz not null default now(),

  constraint constraint_run_key
    primary key (constraint_id, constraint_revision, simulation_dataset_id),
  constraint constraint_run_to_constraint_definition
    foreign key (constraint_id, constraint_revision)
      references merlin.constraint_definition
      on delete cascade,
  constraint constraint_run_to_simulation_dataset
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade,
  constraint constraint_run_requested_by
    foreign key (requested_by)
      references permissions.users
      on update cascade
      on delete set null
);

create index constraint_run_simulation_dataset_id_index
  on merlin.constraint_run (simulation_dataset_id);

comment on table merlin.constraint_run is e''
  'A single constraint run, used to cache violation results to be reused if the constraint definition is not stale.';

comment on column merlin.constraint_run.constraint_id is e''
  'The constraint that we are evaluating during the run.';
comment on column merlin.constraint_run.constraint_revision is e''
  'The version of the constraint definition that was checked.';
comment on column merlin.constraint_run.simulation_dataset_id is e''
  'The simulation dataset id from when the constraint was checked.';
comment on column merlin.constraint_run.results is e''
  'Results that were computed during the constraint check.';
comment on column merlin.constraint_run.requested_by is e''
  'The user who requested the constraint run.';
comment on column merlin.constraint_run.requested_at is e''
  'When the constraint run was created.';

/*************
  Constraints
 *************/

-- Restore plan spec creation function
create or replace function merlin.populate_constraint_spec_new_plan()
returns trigger
language plpgsql as $$
begin
  insert into merlin.constraint_specification (plan_id, constraint_id, constraint_revision)
  select new.id, cms.constraint_id, cms.constraint_revision
  from merlin.constraint_model_specification cms
  where cms.model_id = new.model_id;
  return new;
end;
$$;

----------------
-- Model Spec --
----------------
drop trigger delete_constraints_model_specification on merlin.constraint_model_specification;
drop function merlin.delete_constraints_model_specification_func();
drop trigger update_constraints_model_specification on merlin.constraint_model_specification;
drop function merlin.update_constraints_model_specification_func();
drop trigger insert_constraints_model_specification on merlin.constraint_model_specification;
drop function merlin.insert_constraints_model_specification_func();

-- Keep only the earliest invocation on the spec
delete from merlin.constraint_model_specification
  where invocation_id not in (select min(invocation_id)
                              from merlin.constraint_model_specification
                              group by model_id, constraint_id);

alter table merlin.constraint_model_specification
  drop constraint non_negative_constraint_model_priority,
  drop constraint model_spec_unique_constraint_priority,
  drop column priority,
  drop constraint constraint_model_spec_pkey,
  add constraint constraint_model_spec_pkey primary key (model_id, constraint_id),
  drop column arguments,
  drop column invocation_id;

comment on column merlin.constraint_model_specification.model_id is e''
'The model which this specification is for. Half of the primary key.';
comment on column merlin.constraint_model_specification.constraint_id is e''
'The id of a specific constraint in the specification. Half of the primary key.';

---------------
-- Plan Spec --
---------------

drop trigger delete_constraint_spec on merlin.constraint_specification;
drop function merlin.delete_constraint_spec_func();
drop trigger update_constraint_spec on merlin.constraint_specification;
drop function merlin.update_constraint_spec_func();
drop trigger insert_constraint_spec on merlin.constraint_specification;
drop function merlin.insert_constraint_spec_func();

-- Keep only the earliest invocation on the spec
delete from merlin.constraint_specification
  where invocation_id not in (select min(invocation_id)
                              from merlin.constraint_specification
                              group by plan_id, constraint_id);

alter table merlin.constraint_specification
  drop constraint non_negative_constraint_priority,
  drop constraint constraint_specification_unique_priorities,
  drop constraint constraint_specification_pkey,
  add primary key (plan_id, constraint_id),
  drop column priority,
  drop column arguments,
  drop column invocation_id;

comment on table merlin.constraint_specification is e''
'The set of constraints to be checked for a given plan.';
comment on column merlin.constraint_specification.plan_id is e''
'The plan which this specification is for. Half of the primary key.';
comment on column merlin.constraint_specification.constraint_id is e''
'The id of a specific constraint in the specification. Half of the primary key.';
comment on column merlin.constraint_specification.constraint_revision is e''
'The version of the constraint definition to use. Leave NULL to use the latest version.';
comment on column merlin.constraint_specification.enabled is e''
'Whether to run a given constraint. Defaults to TRUE.';

----------------
-- Definition --
----------------

-- Remove procedural constraints --
delete from merlin.constraint_definition
  where type = 'JAR';
-- Remove Constraints that now have no definitions
delete from merlin.constraint_metadata cm
  where not exists(select from merlin.constraint_definition cd where constraint_id = cm.id );


alter table merlin.constraint_definition
  drop constraint check_constraint_definition_type_consistency,
  drop constraint constraint_procedure_has_uploaded_jar,
  drop column parameter_schema,
  drop column uploaded_jar_id,
  alter column definition set not null,
  drop column type;

comment on column merlin.constraint_definition.definition is e''
  'An executable expression in the Merlin constraint language.';

drop type merlin.constraint_type;

/************
  Scheduling
 ************/

-- Unfix #1633
create or replace function scheduler.update_scheduling_specification_goal_func()
  returns trigger
  language plpgsql as $$
  declare
    next_priority integer;
begin
  select coalesce(
    (select priority
     from scheduler.scheduling_specification_goals ssg
     where ssg.specification_id = new.specification_id
     order by priority desc
     limit 1), -1) + 1
  into next_priority;

  if new.priority > next_priority then
    raise numeric_value_out_of_range using
      message = ('Updated priority % for specification_id % is not consecutive', new.priority, new.specification_id),
      hint = ('The next available priority is %.', next_priority);
  end if;

  if new.priority > old.priority then
    update scheduler.scheduling_specification_goals
    set priority = priority - 1
    where specification_id = new.specification_id
      and priority between old.priority + 1 and new.priority
      and goal_id != new.goal_id;
  else
    update scheduler.scheduling_specification_goals
    set priority = priority + 1
    where specification_id = new.specification_id
      and priority between new.priority and old.priority - 1
      and goal_id != new.goal_id;
  end if;
  return new;
end;
$$;

create or replace function scheduler.update_scheduling_model_specification_goal_func()
  returns trigger
  language plpgsql as $$
  declare
    next_priority integer;
begin
  select coalesce(
    (select priority
     from scheduler.scheduling_model_specification_goals smg
     where smg.model_id = new.model_id
     order by priority desc
     limit 1), -1) + 1
  into next_priority;

  if new.priority > next_priority then
    raise numeric_value_out_of_range using
      message = ('Updated priority % for model_id % is not consecutive', new.priority, new.model_id),
      hint = ('The next available priority is %.', next_priority);
  end if;

  if new.priority > old.priority then
    update scheduler.scheduling_model_specification_goals
    set priority = priority - 1
    where model_id = new.model_id
      and priority between old.priority + 1 and new.priority
      and goal_id != new.goal_id;
  else
    update scheduler.scheduling_model_specification_goals
    set priority = priority + 1
    where model_id = new.model_id
      and priority between new.priority and old.priority - 1
      and goal_id != new.goal_id;
  end if;
  return new;
end;
$$;

-- Undo adding invocation support for mission model
create or replace function scheduler.create_scheduling_spec_for_new_plan()
returns trigger
security definer
language plpgsql as $$
declare
  spec_id integer;
begin
  -- Create a new scheduling specification
  insert into scheduler.scheduling_specification (revision, plan_id, plan_revision, horizon_start, horizon_end,
                                                  simulation_arguments, analysis_only)
  values (0, new.id, new.revision, new.start_time, new.start_time+new.duration, '{}', false)
  returning id into spec_id;

  -- Populate the scheduling specification
  insert into scheduler.scheduling_specification_goals (specification_id, goal_id, goal_revision, priority)
  select spec_id, msg.goal_id, msg.goal_revision, msg.priority
  from scheduler.scheduling_model_specification_goals msg
  where msg.model_id = new.model_id
  order by msg.priority;

  insert into scheduler.scheduling_specification_conditions (specification_id, condition_id, condition_revision)
  select spec_id, msc.condition_id, msc.condition_revision
  from scheduler.scheduling_model_specification_conditions msc
  where msc.model_id = new.model_id;

  return new;
end
$$;

-- Delete invocations beyond the first on the model spec
delete
from scheduler.scheduling_model_specification_goals
where goal_invocation_id not in (
  select min(goal_invocation_id)
  from scheduler.scheduling_model_specification_goals
  group by model_id, goal_id);

-- Reset the model spec table definition
alter table scheduler.scheduling_model_specification_goals
  drop constraint scheduling_model_specification_goals_pkey,
  add primary key (model_id, goal_id),
  drop column goal_invocation_id,
  drop column arguments;

comment on column scheduler.scheduling_model_specification_goals.model_id is e''
'The model which this specification is for. Half of the primary key.';
comment on column scheduler.scheduling_model_specification_goals.goal_id is e''
'The id of a specific scheduling goal in the specification. Half of the primary key.';
comment on column scheduler.scheduling_model_specification_goals.priority is e''
  'The relative priority of the scheduling goal in relation to other goals on the same specification.';

call migrations.mark_migration_rolled_back('16');
