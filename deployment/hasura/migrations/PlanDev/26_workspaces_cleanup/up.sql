-- Remove user sequences table
drop trigger check_locked_delete
on sequencing.user_sequence;
drop function sequencing.check_is_locked_delete;

drop trigger check_locked_update
on sequencing.user_sequence;
drop function sequencing.check_is_locked_update;

drop table sequencing.user_sequence;

call migrations.mark_migration_applied(26);
