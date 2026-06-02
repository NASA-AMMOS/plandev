drop trigger merlin.trg_invalidate_b_on_date_change;
drop function merlin.update_directive_source_is_activity_invalid;
drop view merlin.scheduling_sources;

call migrations.mark_migration_rolled_back(35);
