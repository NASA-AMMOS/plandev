create table merlin.external_source_type_allowed_event_types (
  external_source_type text not null,
  external_event_type text not null,

  constraint external_source_type_allowed_event_types_pkey
    primary key (external_source_type, external_event_type),

  constraint external_event_type_exists
    foreign key (external_event_type)
    references merlin.external_event_type(name)
    on delete restrict,
  constraint external_source_type_exists
    foreign key (external_source_type)
    references merlin.external_source_type(name)
    on delete cascade
);

comment on table merlin.external_source_type_allowed_event_types is e''
  'Describes which event types are allowed in association with a given source type.';

  comment on column merlin.external_source_type_allowed_event_types.external_source_type is e''
    'The external source type that is specifying what event types it may include.';
  comment on column merlin.external_source_type_allowed_event_types.external_event_type is e''
    'An allowed event type for a given external source.';
