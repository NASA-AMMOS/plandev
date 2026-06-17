create table merlin.model_derivation_group (
    model_id integer not null,
    derivation_group_name text not null,

    constraint model_derivation_group_pkey
      primary key (model_id, derivation_group_name),
    constraint mdg_model_exists
      foreign key (model_id)
      references merlin.mission_model(id)
      on delete cascade,
    constraint mdg_derivation_group_exists
      foreign key (derivation_group_name)
      references merlin.derivation_group(name)
      on update cascade
      on delete restrict
);

comment on table merlin.model_derivation_group is e''
  'The default set of derivation groups that any new plans created using this model would have linked to them.';

comment on column merlin.model_derivation_group.model_id is e''
  'The model with which the derivation group is associated.';
comment on column merlin.model_derivation_group.derivation_group_name is e''
  'The derivation group being associated with the model.';
