create table scheduler.activity_scheduling_source (
  -- details of activity directive
  directive_id integer not null,
  plan_id integer not null,

  -- details of the scheduling goal
  source_scheduling_goal_id integer,
  source_scheduling_goal_invocation_id integer default null,
  -- analysis id???

  -- details of the actual source


)
