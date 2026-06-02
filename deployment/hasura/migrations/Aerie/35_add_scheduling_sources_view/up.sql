create view merlin.scheduling_sources as (
  select
  	scheduled_directive_id,
  	scheduled_plan_id,
  	jsonb_agg(sources) as sources
  from (
  	select a.scheduled_directive_id, a.scheduled_plan_id, jsonb_build_object('type', 'activity', 'value', a.referenced_directive_id) AS sources
  	from merlin.directive_source_is_activity as a
  	union all

  	select r.scheduled_directive_id, r.scheduled_plan_id, jsonb_build_object('type', 'resource', 'value', r.referenced_resource_name)
  	from merlin.directive_source_is_resource_type as r
  	union all

  	select e.scheduled_directive_id, e.scheduled_plan_id,
  			to_jsonb(
  				jsonb_build_object(
  					'type', 'external event',
  					'value', jsonb_build_object(
  						'referenced_event_key', e.referenced_event_key,
  						'referenced_event_type', e.referenced_event_type,
  						'referenced_event_derivation_group', e.referenced_event_derivation_group,
  						'referenced_event_source_key', e.referenced_event_source_key,
  						'referenced_event_source_created_at', e.referenced_event_source_created_at
  					)
  				)
  			)
  	from merlin.directive_source_is_external_event as e
  )
  group by scheduled_directive_id, scheduled_plan_id
);

CREATE OR REPLACE FUNCTION merlin.update_directive_source_is_activity_invalid()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    -- Only act if the value actually changed
    IF NEW.start_offset IS DISTINCT FROM OLD.start_offset THEN
        UPDATE merlin.directive_source_is_activity
        SET valid = false
        WHERE scheduled_directive_id = NEW.id and scheduled_plan_id = NEW.plan_id;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER merlin.trg_invalidate_b_on_date_change
	AFTER UPDATE OF start_offset
	ON merlin.activity_directive
FOR EACH ROW
	EXECUTE FUNCTION merlin.update_directive_source_is_activity_invalid();


call migrations.mark_migration_applied(35);
