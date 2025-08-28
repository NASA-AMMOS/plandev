-- Default Roles:
insert into permissions.user_roles(role) values ('aerie_admin'), ('user'), ('viewer');

-- Permissions For Default Roles:
-- 'aerie_admin' permissions aren't specified since 'aerie_admin' is always considered to have "NO_CHECK" permissions
update permissions.user_role_permission
set action_permissions = '{}',
    function_permissions = '{}'
where role = 'aerie_admin';

update permissions.user_role_permission
set action_permissions = '{
      "check_constraints": "PLAN_OWNER_COLLABORATOR",
      "create_expansion_rule": "NO_CHECK",
      "create_expansion_set": "NO_CHECK",
      "expand_all_activities": "NO_CHECK",
      "expand_all_templates": "NO_CHECK",
      "assign_activities_by_filter": "NO_CHECK",
      "insert_ext_dataset": "PLAN_OWNER",
      "resource_samples": "NO_CHECK",
      "schedule":"PLAN_OWNER_COLLABORATOR",
      "sequence_seq_json_bulk": "NO_CHECK",
      "simulate":"PLAN_OWNER_COLLABORATOR"
    }',
    function_permissions = '{
      "apply_preset": "PLAN_OWNER_COLLABORATOR",
      "begin_merge": "PLAN_OWNER_TARGET",
      "branch_plan": "NO_CHECK",
      "cancel_merge": "PLAN_OWNER_TARGET",
      "commit_merge": "PLAN_OWNER_TARGET",
      "create_merge_rq": "PLAN_OWNER_SOURCE",
      "create_snapshot": "PLAN_OWNER_COLLABORATOR",
      "delete_activity_reanchor": "PLAN_OWNER_COLLABORATOR",
      "delete_activity_reanchor_bulk": "PLAN_OWNER_COLLABORATOR",
      "delete_activity_reanchor_plan": "PLAN_OWNER_COLLABORATOR",
      "delete_activity_reanchor_plan_bulk": "PLAN_OWNER_COLLABORATOR",
      "delete_activity_subtree": "PLAN_OWNER_COLLABORATOR",
      "delete_activity_subtree_bulk": "PLAN_OWNER_COLLABORATOR",
      "deny_merge": "PLAN_OWNER_TARGET",
      "get_conflicting_activities": "NO_CHECK",
      "get_non_conflicting_activities": "NO_CHECK",
      "get_plan_history": "NO_CHECK",
      "migrate_plan_to_model": "PLAN_OWNER_COLLABORATOR",
      "restore_activity_changelog": "PLAN_OWNER_COLLABORATOR",
      "restore_snapshot": "PLAN_OWNER_COLLABORATOR",
      "set_resolution": "PLAN_OWNER_TARGET",
      "set_resolution_bulk": "PLAN_OWNER_TARGET",
      "withdraw_merge_rq": "PLAN_OWNER_SOURCE"
    }'
where role = 'user';

update permissions.user_role_permission
set action_permissions = '{
      "sequence_seq_json_bulk": "NO_CHECK",
      "resource_samples": "NO_CHECK"
    }',
    function_permissions = '{
      "get_conflicting_activities": "NO_CHECK",
      "get_non_conflicting_activities": "NO_CHECK",
      "get_plan_history": "NO_CHECK"
    }'
where role = 'viewer';

-- Default Users:
insert into permissions.users(username, default_role)
  values ('Mission Model', 'viewer'),
         ('Aerie Legacy', 'viewer');
