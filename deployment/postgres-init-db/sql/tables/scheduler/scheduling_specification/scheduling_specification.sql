create table scheduler.scheduling_specification (
  id integer generated always as identity,
  revision integer not null default 0,

  plan_id integer not null,
  plan_revision integer not null,
  horizon_start timestamptz not null,
  horizon_end timestamptz not null,
  simulation_arguments jsonb not null,
  analysis_only boolean not null,
  constraint scheduling_specification_synthetic_key
    primary key(id),
  constraint scheduling_specification_unique_plan_id
    unique (plan_id),
  constraint scheduling_spec_plan_id_fkey
    foreign key (plan_id)
    references merlin.plan
    on update cascade
    on delete cascade
);

comment on table scheduler.scheduling_specification is e''
  'The specification for a scheduling run.';
comment on column scheduler.scheduling_specification.id is e''
  'The synthetic identifier for this scheduling specification.';
comment on column scheduler.scheduling_specification.revision is e''
  'A monotonic clock that ticks for every change to this scheduling specification.';
comment on column scheduler.scheduling_specification.plan_id is e''
  'The ID of the plan to be scheduled.';
comment on column scheduler.scheduling_specification.horizon_start is e''
  'The start of the scheduling horizon within which the scheduler may place activities.';
comment on column scheduler.scheduling_specification.horizon_end is e''
  'The end of the scheduling horizon within which the scheduler may place activities.';
comment on column scheduler.scheduling_specification.simulation_arguments is e''
  'The arguments to use for simulation during scheduling.';
comment on column scheduler.scheduling_specification.analysis_only is e''
  'The boolean stating whether this is an analysis run only';

create function scheduler.create_scheduling_spec_for_new_plan()
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
  insert into scheduler.scheduling_specification_goals (specification_id, goal_id, goal_revision, priority, arguments)
  select spec_id, msg.goal_id, msg.goal_revision, msg.priority, msg.arguments
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

comment on function scheduler.create_scheduling_spec_for_new_plan() is e''
'Creates a scheduling specification for a new plan
 and populates it with the contents of the plan''s model''s specification.';

create trigger scheduling_spec_for_new_plan_trigger
after insert on merlin.plan
for each row
execute function scheduler.create_scheduling_spec_for_new_plan();

create trigger increment_revision_on_update_trigger
  before update on scheduler.scheduling_specification
  for each row
  when (pg_trigger_depth() < 1)
  execute function util_functions.increment_revision_update();
