-- Remove user sequences table
drop table sequencing.user_sequence;

call migrations.mark_migration_applied(25);
