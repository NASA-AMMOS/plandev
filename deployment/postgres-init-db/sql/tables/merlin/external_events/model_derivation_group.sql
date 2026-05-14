-- just for defaults for a mission. NOT replacing plan_derivation_group at all.
create table merlin.model_derivation_group (
    model_id integer not null,
    derivation_group_name text not null,

    constraint model_derivation_group_pkey
      primary key (model_id, derivation_group_name),
    constraint pdg_model_exists
      foreign key (model_id)
      references merlin.mission_model(id)
      on delete cascade,
    constraint pdg_derivation_group_exists
      foreign key (derivation_group_name)
      references merlin.derivation_group(name)
      on update cascade
      on delete restrict
);

comment on table merlin.model_derivation_group is e''
  'Links externally imported event sources & models.\n'
  'Additionally, tracks the last time a model owner/contributor(s) have acknowledged additions to the derivation group.\n';

comment on column merlin.model_derivation_group.model_id is e''
  'The model with which the derivation group is associated.';
comment on column merlin.model_derivation_group.derivation_group_name is e''
  'The derivation group being associated with the model.';
