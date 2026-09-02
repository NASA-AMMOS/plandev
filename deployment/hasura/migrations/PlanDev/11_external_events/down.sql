-- up.sql creates table and sequence, delete them
drop trigger refresh_derived_events_on_derivation_group on merlin.derivation_group;
drop trigger refresh_derived_events_on_external_source on merlin.external_source;
drop trigger refresh_derived_events_on_external_event on merlin.external_event;
drop function merlin.refresh_derived_events_on_trigger cascade;
drop materialized view merlin.derived_events;

drop function merlin.subtract_later_ranges cascade;

drop trigger external_source_pdg_association_delete on merlin.external_source;
drop function merlin.external_source_pdg_association_delete cascade;
drop trigger external_source_pdg_ack_update on merlin.external_source;
drop function merlin.external_source_pdg_ack_update cascade;

drop trigger pdg_update_ack_at on merlin.plan_derivation_group;
drop function merlin.pdg_update_ack_at cascade;
drop table merlin.plan_derivation_group cascade;

drop trigger check_external_event_boundaries on merlin.external_event;
drop function merlin.check_external_event_boundaries cascade;

drop trigger check_external_event_duration_is_nonnegative_trigger on merlin.external_event;
drop table merlin.external_event cascade;

drop trigger external_source_type_matches_dg_on_add on merlin.external_source;
drop function merlin.external_source_type_matches_dg_on_add;
drop table merlin.external_source cascade;

drop table merlin.derivation_group cascade;
drop table merlin.external_event_type cascade;
drop table merlin.external_source_type cascade;

call migrations.mark_migration_rolled_back('11');
