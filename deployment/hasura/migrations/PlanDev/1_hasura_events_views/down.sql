drop view hasura.refresh_resource_type_logs;
drop view hasura.refresh_model_parameter_logs;
drop view hasura.refresh_activity_type_logs;

drop function hasura.get_event_logs(_trigger_name text);

call migrations.mark_migration_rolled_back('1');
