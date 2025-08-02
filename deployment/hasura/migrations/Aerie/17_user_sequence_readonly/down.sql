drop trigger check_locked_delete on sequencing.user_sequence;
drop function sequencing.check_is_locked_delete();
drop trigger check_locked_update on sequencing.user_sequence;
drop function sequencing.check_is_locked_update();

create trigger set_timestamp
before update on sequencing.user_sequence
for each row
execute function util_functions.set_updated_at();

alter table sequencing.user_sequence
drop column is_locked;

call migrations.mark_migration_rolled_back('17');
