create table actions.action_configuration (
  name text not null,
  description text null,
  action_parameters jsonb default '{}'::jsonb,
  action_settings jsonb default '{}'::jsonb,
  action_definition_id integer not null,

  constraint action_parameter_configuration_primary_key
    primary key (name, action_definition_id),

  foreign key (action_definition_id)
    references actions.action_definition (id)
    on delete cascade
);

comment on table actions.action_configuration is e''
  'Configuration of an action''s parameters and/or settings to be used during a run.';
comment on column actions.action_configuration.name is e''
  'The name of the configuration.';
comment on column actions.action_configuration.description is e''
  'The description of the configuration.';
comment on column actions.action_configuration.action_parameters is e''
  'The parameter configuration for the action.';
comment on column actions.action_configuration.action_settings is e''
  'The settings configuration for the action.';
comment on column actions.action_configuration.action_definition_id is e''
  'The ID of the action the configuration is for.';

create schema deliveries;

create table deliveries.target (
  name text not null,

  constraint delivery_target_primary_key
    primary key (name)
);

comment on table deliveries.target is e''
  'A target system to for deliveries to be sent to.';
comment on column deliveries.target.name is e''
  'The name of the target.';

create table deliveries.delivery (
  id integer generated always as identity,
  name text,
  status text not null,
  target text not null,
  updated_at timestamptz not null default now(),
  updated_by text,

  constraint delivery_primary_key
    primary key (id),
  constraint target_fkey
    foreign key (target) references deliveries.target
    on update cascade
    on delete cascade,
  foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

create trigger set_timestamp
  before update on deliveries.delivery
  for each row
  execute function util_functions.set_updated_at();

comment on table deliveries.delivery is e''
  'A delivery.';
comment on column deliveries.delivery.id is e''
  'The ID for the delivery.';
comment on column deliveries.delivery.name is e''
  'An optional name to describe the delivery.';
comment on column deliveries.delivery.status is e''
  'The status of the delivery.';
comment on column deliveries.delivery.target is e''
  'The target to send the delivery to.';
comment on column deliveries.delivery.updated_at is e''
  'The last time the delivery was updated.';
comment on column deliveries.delivery.updated_by is e''
  'The user who last updated the delivery.';

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

create table deliveries.file_to_delivery (
    filename text not null,
    delivery_id int not null,

    constraint file_to_delivery_primary_key
      primary key (filename, delivery_id),
    constraint delivery_id_fkey
      foreign key (delivery_id)
      references deliveries.delivery (id)
      on delete cascade
);

comment on table deliveries.file_to_delivery is e''
  'Join table for files and deliveries.';
comment on column deliveries.file_to_delivery.filename is e''
  'Name of the joining file.';
comment on column deliveries.file_to_delivery.delivery_id is e''
  'ID of the joining delivery.';

call  migrations.mark_migration_applied('28');
