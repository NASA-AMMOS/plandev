create table merlin.directive_source_is_external_event
(
    scheduled_directive_id integer NOT NULL,
    scheduled_plan_id integer NOT NULL,
    referenced_event_key text NOT NULL,
    referenced_event_type text NOT NULL,
    referenced_event_derivation_group text NOT NULL,
    referenced_event_source_key text NOT NULL,
    referenced_event_source_created_at timestamp with time zone NOT NULL,

    constraint directive_source_is_external_event_pkey
      primary key (referenced_event_source_key, referenced_event_derivation_group,
                    referenced_event_type, referenced_event_key, scheduled_plan_id,
                    scheduled_directive_id, referenced_event_source_created_at),
    constraint referenced_event_exists
      foreign key (referenced_event_key, referenced_event_type, referenced_event_derivation_group,
                    referenced_event_source_key, referenced_event_source_created_at)
      references merlin.external_event (key, event_type_name, derivation_group_name, source_key, source_created_at)
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
