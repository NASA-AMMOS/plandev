drop type permissions.action_permission_key;

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

update permissions.user_role_permission
set action_permissions = action_permissions
    || jsonb_build_object('expand_all_templates', 'NO_CHECK')
    || jsonb_build_object('assign_activities_by_filter', 'NO_CHECK')
where role = 'user';

call migrations.mark_migration_applied('22');
