create index activity_directive_anchor_id_index
  on merlin.activity_directive (anchor_id, plan_id)
  where anchor_id is not null;

call migrations.mark_migration_applied(35);
