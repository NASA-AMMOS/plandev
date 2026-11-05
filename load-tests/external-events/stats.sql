select
  (select count(*) from merlin.derivation_group where name like 'benchmark-group-%') as derivation_groups,
  (select count(*) from merlin.external_source where derivation_group_name like 'benchmark-group-%') as external_sources,
  (select count(*) from merlin.external_event where derivation_group_name like 'benchmark-group-%') as external_events,
  (select count(*) from merlin.derived_events where derivation_group_name like 'benchmark-group-%') as derived_events;
