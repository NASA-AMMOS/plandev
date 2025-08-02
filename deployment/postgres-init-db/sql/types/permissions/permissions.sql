-- User Role Permissions Validation assumes that the Plan Merge Permissions
-- are covered by the range [PLAN_OWNER_SOURCE - PLAN_OWNER_COLLABORATOR_TARGET]
create type permissions.permission
  as enum (
    'NO_CHECK',
    'OWNER',
    'MISSION_MODEL_OWNER',
    'PLAN_OWNER',
    'PLAN_COLLABORATOR',
    'PLAN_OWNER_COLLABORATOR',
    'PLAN_OWNER_SOURCE',
    'PLAN_COLLABORATOR_SOURCE',
    'PLAN_OWNER_COLLABORATOR_SOURCE',
    'PLAN_OWNER_TARGET',
    'PLAN_COLLABORATOR_TARGET',
    'PLAN_OWNER_COLLABORATOR_TARGET'
  );

create type permissions.action_permission_key
  as enum (
    'assign_activities_by_filter',
    'check_constraints',
    'create_expansion_rule',
    'create_expansion_set',
    'expand_all_activities',
    'expand_all_templates',
    'insert_ext_dataset',
    'resource_samples',
    'schedule',
    'sequence_seq_json_bulk',
    'simulate'
  );

create type permissions.function_permission_key
  as enum (
    'apply_preset',
    'begin_merge',
    'branch_plan',
    'cancel_merge',
    'commit_merge',
    'create_merge_rq',
    'create_snapshot',
    'delete_activity_reanchor',
    'delete_activity_reanchor_bulk',
    'delete_activity_reanchor_plan',
    'delete_activity_reanchor_plan_bulk',
    'delete_activity_subtree',
    'delete_activity_subtree_bulk',
    'deny_merge',
    'get_conflicting_activities',
    'get_non_conflicting_activities',
    'get_plan_history',
    'migrate_plan_to_model',
    'restore_activity_changelog',
    'restore_snapshot',
    'set_resolution',
    'set_resolution_bulk',
    'withdraw_merge_rq'
  );
