create table deliveries.action_to_delivery (
  delivery_id int not null,
  configuration_name text null,
  action_id int not null,

  constraint action_to_delivery_pkey
    primary key (delivery_id, action_id),
  constraint delivery_id_fkey
    foreign key (delivery_id)
    references deliveries.delivery (id)
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

comment on table deliveries.action_to_delivery is e''
  'Join table for actions and deliveries.';
comment on column deliveries.action_to_delivery.delivery_id is e''
  'ID of the delivery being linked.';
comment on column deliveries.action_to_delivery.configuration_name is e''
  'Optional, name of the configuration to use for the action.';
comment on column deliveries.action_to_delivery.action_id is e''
  'ID of the action being linked.';
