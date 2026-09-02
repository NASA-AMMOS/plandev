-- Span Table update
alter table merlin.span
  rename column id to span_id;

alter table merlin.span
  alter column span_id drop identity;

drop view merlin.simulated_activity;
create view merlin.simulated_activity as
(
select span.span_id as id,
       sd.id as simulation_dataset_id,
       span.parent_id as parent_id,
       span.start_offset as start_offset,
       span.duration as duration,
       span.attributes as attributes,
       span.type as activity_type_name,
       (span.attributes#>>'{directiveId}')::integer as directive_id,
       sd.simulation_start_time + span.start_offset as start_time,
       sd.simulation_start_time + span.start_offset + span.duration as end_time
from merlin.span span
       join merlin.dataset d on span.dataset_id = d.id
       join merlin.simulation_dataset sd on d.id = sd.dataset_id
       join merlin.simulation s on s.id = sd.simulation_id
  );

-- event table update
alter table merlin.event
  add column span_id integer;

call migrations.mark_migration_applied('8')
