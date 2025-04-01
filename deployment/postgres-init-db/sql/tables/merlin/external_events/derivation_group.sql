create table merlin.derivation_group (
    name text not null,
    source_type_name text not null,
    owner text,

    constraint derivation_group_pkey
      primary key (name),
    constraint derivation_group_references_external_source_type
      foreign key (source_type_name)
      references merlin.external_source_type(name)
      on update cascade
      on delete restrict,
    constraint derivation_group_owner_exists
      foreign key (owner) references permissions.users
      on update cascade
      on delete set null
);

comment on table merlin.derivation_group is e''
  'A collection of external sources of the same type that the derivation operation is run against.';

comment on column merlin.derivation_group.name is e''
  'The name and primary key of the derivation group.';
comment on column merlin.derivation_group.source_type_name is e''
  'The name of the external_source_type of sources in this derivation group.';
comment on column merlin.derivation_group.owner is e''
  'The name of the user that created this derivation_group.';
