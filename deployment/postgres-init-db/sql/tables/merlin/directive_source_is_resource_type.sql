create table merlin.directive_source_is_resource_type
(
    scheduled_directive_id integer NOT NULL,
    scheduled_plan_id integer NOT NULL,
    referenced_resource_name text NOT NULL,
    referenced_resource_model_id integer NOT NULL,

    constraint directive_source_is_resource_type_pkey
      primary key (scheduled_directive_id, scheduled_plan_id, referenced_resource_name, referenced_resource_model_id),

    constraint referenced_resource_exists
      foreign key (referenced_resource_name, referenced_resource_model_id)
      references merlin.resource_type (name, model_id)
      on update cascade
      on delete set null,
    constraint scheduled_directive_exists
      foreign key (scheduled_directive_id, scheduled_plan_id)
      references merlin.activity_directive (id, plan_id)
      on update cascade
      on delete set null,
    constraint plan_exists
      foreign key (scheduled_plan_id)
      references merlin.plan (id)
      on update cascade
      on delete set null
);
