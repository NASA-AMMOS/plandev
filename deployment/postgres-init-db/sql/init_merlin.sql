/*
  The order of inclusion is important!
    - Types must be loaded before usage in tables or function returns
    - Tables must be loaded before being referenced by foreign keys.
    - Functions must be loaded before they're used in triggers, but can be loaded after any functions that call them.
    - Views must be loaded after all their dependent tables and functions
 */
begin;
  -- Domain Types
  \ir types/merlin/merlin-arguments.sql
  \ir types/merlin/activity-directive-metadata.sql
  \ir types/merlin/plan-merge-types.sql
  \ir types/merlin/constraint_type.sql

  ------------
  -- Tables
  -- Uploaded files (JARs or simulation input files)
  \ir tables/merlin/uploaded_file.sql

  -- Mission Model
  \ir tables/merlin/mission_model.sql
  \ir tables/merlin/mission_model_parameters.sql
  \ir tables/merlin/activity_type.sql
  \ir tables/merlin/resource_type.sql

  -- Plan
  \ir tables/merlin/plan.sql
  \ir tables/merlin/plan_collaborators.sql

  -- Scheduling Goals and Scheduling Goal Specification
  \ir init_scheduler_mid_merlin.sql
  
  -- Activity Directives
  \ir tables/merlin/activity_directive/activity_directive_metadata_schema.sql
  \ir tables/merlin/activity_directive/activity_directive.sql
  \ir tables/merlin/activity_directive/activity_directive_changelog.sql
  \ir tables/merlin/activity_directive/activity_directive_validations.sql
  \ir tables/merlin/activity_directive/anchor_validation_status.sql
  \ir tables/merlin/activity_directive/activity_presets.sql
  \ir tables/merlin/activity_directive/preset_to_directive.sql

  -- Datasets
  \ir tables/merlin/dataset/dataset.sql
  \ir tables/merlin/dataset/event.sql
  \ir tables/merlin/dataset/topic.sql
  \ir tables/merlin/dataset/span.sql
  \ir tables/merlin/dataset/profile.sql
  \ir tables/merlin/dataset/profile_segment.sql

  -- Simulation
  \ir tables/merlin/simulation/simulation_template.sql
  \ir tables/merlin/simulation/simulation.sql
  \ir tables/merlin/simulation/simulation_dataset.sql
  \ir tables/merlin/simulation/simulation_extent.sql

  -- External Datasets
  \ir tables/merlin/plan_dataset.sql

  -- Constraints
  \ir tables/merlin/constraints/constraint_metadata.sql
  \ir tables/merlin/constraints/constraint_definition.sql
  \ir tables/merlin/constraints/constraint_model_specification.sql
  \ir tables/merlin/constraints/constraint_specification.sql
  \ir tables/merlin/constraints/constraint_request.sql
  \ir tables/merlin/constraints/constraint_results.sql
  \ir tables/merlin/constraints/constraint_run.sql

  -- Snapshots
  \ir tables/merlin/snapshot/plan_snapshot.sql
  \ir tables/merlin/snapshot/plan_snapshot_parent.sql
  \ir tables/merlin/snapshot/plan_latest_snapshot.sql
  \ir tables/merlin/snapshot/plan_snapshot_activities.sql
  \ir tables/merlin/snapshot/preset_to_snapshot_directive.sql

  -- Merging
  \ir tables/merlin/merging/merge_request.sql
  \ir tables/merlin/merging/merge_comments.sql
  \ir tables/merlin/merging/merge_staging_area.sql
  \ir tables/merlin/merging/conflicting_activities.sql

  -- External Events
  \ir tables/merlin/external_events/external_source_type.sql
  \ir tables/merlin/external_events/external_event_type.sql
  \ir tables/merlin/external_events/derivation_group.sql
  \ir tables/merlin/external_events/external_source.sql
  \ir tables/merlin/external_events/external_event.sql
  \ir tables/merlin/external_events/plan_derivation_group.sql

  ------------
  -- Functions
  \ir functions/merlin/reanchoring_functions.sql
  \ir functions/merlin/external_events/subtract_later_ranges.sql

  -- Snapshots
  \ir functions/merlin/snapshots/create_snapshot.sql
  \ir functions/merlin/snapshots/plan_history_functions.sql
  \ir functions/merlin/snapshots/restore_from_snapshot.sql

  -- Merging
  \ir functions/merlin/merging/plan_locked_exception.sql
  \ir functions/merlin/merging/duplicate_plan.sql
  \ir functions/merlin/merging/get_merge_base.sql
  \ir functions/merlin/merging/merge_request_state_functions.sql
  \ir functions/merlin/merging/begin_merge.sql
  \ir functions/merlin/merging/commit_merge.sql

  ------------
  -- Views
  \ir views/merlin/activity_directive_extended.sql
  \ir views/merlin/simulated_activity.sql
  \ir views/merlin/resource_profile.sql
  \ir views/merlin/derived_events.sql
end;
