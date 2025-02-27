/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Types
  \ir types/scheduler/goal_type.sql

  -- Tables
  -- Scheduling Goals
  \ir tables/scheduler/scheduling_goal_metadata.sql
  \ir tables/scheduler/scheduling_goal_definition.sql

  -- Scheduling Conditions
  \ir tables/scheduler/scheduling_condition_metadata.sql
  \ir tables/scheduler/scheduling_condition_definition.sql

  -- Scheduling Specification
  \ir tables/scheduler/scheduling_specification/scheduling_specification.sql
  \ir tables/scheduler/scheduling_specification/scheduling_specification_goals.sql
  \ir tables/scheduler/scheduling_specification/scheduling_specification_conditions.sql
  \ir tables/scheduler/scheduling_specification/scheduling_model_specification_conditions.sql
  \ir tables/scheduler/scheduling_specification/scheduling_model_specification_goals.sql

  -- Scheduling Output
  \ir tables/scheduler/scheduling_run/scheduling_request.sql
  \ir tables/scheduler/scheduling_run/scheduling_goal_analysis.sql
  \ir tables/scheduler/scheduling_run/scheduling_goal_analysis_created_activities.sql
  \ir tables/scheduler/scheduling_run/scheduling_goal_analysis_satisfying_activities.sql
end;
