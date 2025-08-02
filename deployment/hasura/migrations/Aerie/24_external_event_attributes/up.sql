alter table merlin.external_source_type add column attribute_schema jsonb not null default '{ "type": "object", "required": [], "properties": {} }';
comment on column merlin.external_source_type.attribute_schema is e''
  'The JSON schema used to validate attributes for sources using this source type.';

alter table merlin.external_event_type add column attribute_schema jsonb not null default '{ "type": "object", "required": [], "properties": {} }';
comment on column merlin.external_event_type.attribute_schema is e''
  'The JSON schema used to validate attributes for events using this event type.';

alter table merlin.external_source add column attributes jsonb not null default '{}';
comment on column merlin.external_source.attributes is e''
  'Additional data captured from the original external source, in key/pair form.';

alter table merlin.external_event add column attributes jsonb not null default '{}';
comment on column merlin.external_event.attributes is e''
  'Additional data captured from the original external event, in key/pair form.';

drop materialized view merlin.derived_events;

create materialized view merlin.derived_events as
  -- "distinct on (event_key, derivation_group_name)" and "order by valid_at" satisfies rule 4
-- (only the most recently valid version of an event is included)
select distinct on (event_key, derivation_group_name)
  output.event_key,
  output.source_key,
  output.derivation_group_name,
  output.event_type_name,
  output.duration,
  output.start_time,
  output.source_range,
  output.valid_at,
  output.attributes
from (
       -- select the events from the sources and include them as they fit into the ranges determined by sub
       select
         s.key as source_key,
         ee.key as event_key,
         ee.event_type_name,
         ee.duration,
         s.derivation_group_name,
         ee.start_time,
         s.source_range,
         s.valid_at,
         ee.attributes
       from merlin.external_event ee
              join (
         with base_ranges as (
           -- base_ranges orders sources by their valid time
           -- and extracts the multirange that they are stated to be valid over
           select
             external_source.key,
             external_source.derivation_group_name,
             tstzmultirange(tstzrange(external_source.start_time, external_source.end_time)) as range,
             external_source.valid_at
           from merlin.external_source
           order by external_source.valid_at
         ), base_and_sub_ranges as (
           -- base_and_sub_ranges takes each of the sources above and compiles a list of all the sources that follow it
           -- and their multiranges that they are stated to be valid over
           select
             base.key,
             base.derivation_group_name,
             base.range as original_range,
             array_remove(array_agg(subsequent.range order by subsequent.valid_at), NULL) as subsequent_ranges,
             base.valid_at
           from base_ranges base
                  left join base_ranges subsequent
                            on base.derivation_group_name = subsequent.derivation_group_name
                              and base.valid_at < subsequent.valid_at
           group by base.key, base.derivation_group_name, base.valid_at, base.range
         )
         -- this final selection (s) utilizes the first, as well as merlin.subtract_later_ranges,
         -- to produce a sparse multirange that a given source is valid over.
         -- See merlin.subtract_later_ranges for further details on subtracted ranges.
         select
           r.key,
           r.derivation_group_name,
           merlin.subtract_later_ranges(r.original_range, r.subsequent_ranges) as source_range,
           r.valid_at
         from base_and_sub_ranges r
         order by r.derivation_group_name desc, r.valid_at) s
                   on s.key = ee.source_key
                     and s.derivation_group_name = ee.derivation_group_name
       where s.source_range @> ee.start_time
       order by valid_at desc
     ) output;

-- create a unique index, which allows concurrent refreshes
create unique index on merlin.derived_events (
  event_key,
  source_key,
  derivation_group_name,
  event_type_name
);

call migrations.mark_migration_applied('24');
