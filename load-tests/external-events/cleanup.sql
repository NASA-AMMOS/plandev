\timing on
\echo 'Removing external-event benchmark data...'

begin;

delete from merlin.external_source
where derivation_group_name like 'benchmark-group-%';

delete from merlin.derivation_group
where name like 'benchmark-group-%';

delete from merlin.external_event_type
where name = 'benchmark-event-type';

delete from merlin.external_source_type
where name = 'benchmark-source-type';

commit;
