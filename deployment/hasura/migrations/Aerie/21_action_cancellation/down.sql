drop trigger notify_action_run_cancel_requested on actions.action_run;
drop function actions.notify_action_run_cancel_requested();

alter table actions.action_run
drop column canceled;

call migrations.mark_migration_rolled_back('21');
