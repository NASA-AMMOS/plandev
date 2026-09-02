update permissions.user_role_permission
set action_permissions = action_permissions - 'expand_all_templates' - 'assign_activities_by_filter';

drop type permissions.action_permission_key;

create type permissions.action_permission_key
  as enum (
    'check_constraints',
    'create_expansion_rule',
    'create_expansion_set',
    'expand_all_activities',
    'insert_ext_dataset',
    'resource_samples',
    'schedule',
    'sequence_seq_json_bulk',
    'simulate'
  );

call migrations.mark_migration_rolled_back('22');
