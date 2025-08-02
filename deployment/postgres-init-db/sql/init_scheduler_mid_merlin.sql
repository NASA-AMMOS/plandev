-- This file is called from inside init_merlin.sql
begin;
  -- Scheduling Goals and Scheduling Goal Specification
  -- Done here as Activity Directives depends on these tables
  \ir types/scheduler/goal_type.sql
  \ir tables/scheduler/scheduling_goal_metadata.sql
  \ir tables/scheduler/scheduling_goal_definition.sql
  \ir tables/scheduler/scheduling_specification/scheduling_specification.sql
  \ir tables/scheduler/scheduling_specification/scheduling_specification_goals.sql