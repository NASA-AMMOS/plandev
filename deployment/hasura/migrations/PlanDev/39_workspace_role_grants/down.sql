drop table sequencing.workspace_role_grants;
drop view permissions.role_members;

call migrations.mark_migration_rolled_back(39);
