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
  'The ID of the action the configuration is for.'