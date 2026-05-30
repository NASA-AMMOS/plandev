create view merlin.scheduling_sources as
(
  select
    a.scheduled_directive_id,
    array_agg(concat('a: ', text(a.referenced_directive_id)))
      || array_agg(concat('r: ', r.referenced_resource_name)) as sources
  from
    merlin.directive_source_is_activity as a
  join
    merlin.directive_source_is_resource_type as r
  on a.scheduled_directive_id = r.scheduled_directive_id
  group by a.scheduled_directive_id, a.scheduled_plan_id, r.referenced_resource_model_id
);


call migrations.mark_migration_applied(35);
