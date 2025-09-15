create table deliveries.action_to_target (
  target_name text not null,
  configuration_name text null,
  action_id int not null,

  constraint action_to_target_pkey
    primary key (target_name, action_id),
  constraint target_name_fkey
    foreign key (target_name)
    references deliveries.target (name)
    on delete cascade,
  constraint configuration_name_fkey
    foreign key (configuration_name, action_id)
    references actions.action_configuration (name, action_definition_id)
    on delete cascade,
  constraint action_id_fkey
    foreign key (action_id)
    references actions.action_definition (id)
    on delete cascade
);

comment on table deliveries.action_to_target is e''
  'Join table for actions and targets.';
comment on column deliveries.action_to_target.target_name is e''
  'Name of the target being linked.';
comment on column deliveries.action_to_target.configuration_name is e''
  'Optional, name of the configuration to use for the action.';
comment on column deliveries.action_to_target.action_id is e''
  'ID of the action being linked.';
