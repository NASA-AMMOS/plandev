alter table sequencing.user_sequence
  drop constraint user_sequence_workspace_id_fkey,
  drop column workspace_id;

drop trigger set_timestamp on sequencing.workspace;
drop table sequencing.workspace;

call migrations.mark_migration_rolled_back('9');
