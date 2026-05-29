drop index if exists merlin.activity_directive_anchor_id_index;

call migrations.mark_migration_rolled_back(35);
