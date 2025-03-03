drop trigger notify_action_run_inserted on ui.action_run;
drop function ui.notify_action_run_inserted cascade;

drop table ui.action_run cascade;

drop trigger set_timestamp on ui.action_definition;
drop table ui.action_definition cascade;

drop type ui.action_run_status;

call migrations.mark_migration_rolled_back('14');
