create table merlin.directive_source_is_activity
(
    scheduled_directive_id integer NOT NULL,
    scheduled_plan_id integer NOT NULL,
    referenced_directive_id integer NOT NULL,

    constraint directive_source_is_activity_pkey
      primary key (scheduled_plan_id, referenced_directive_id, scheduled_directive_id),

    constraint referenced_directive_exists
      foreign key (referenced_directive_id, scheduled_plan_id)
      references merlin.activity_directive (id, plan_id)
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
