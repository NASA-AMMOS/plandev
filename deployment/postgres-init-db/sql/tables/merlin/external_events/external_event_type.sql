create table merlin.external_event_type (
    name text not null,
    attribute_schema jsonb,

    constraint external_event_type_pkey
      primary key (name)
);

comment on table merlin.external_event_type is e''
  'Externally imported event types.';

comment on column merlin.external_event_type.name is e''
  'The identifier for this external_event_type, as well as its name.';

comment on column merlin.external_event_type.attribute_schema is e''
  'The JSON schema used to validate attributes for events using this event type.';
