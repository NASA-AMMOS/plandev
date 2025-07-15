create table merlin.plan_snapshot_activities(
   snapshot_id integer
      references merlin.plan_snapshot
      on delete cascade,
    id integer,

    name text,
    source_scheduling_goal_id integer,
    source_scheduling_goal_invocation_id integer,
    created_at timestamptz not null,
    created_by text,
    last_modified_at timestamptz not null,
    last_modified_by text,
    start_offset interval not null,
    type text not null,
    arguments merlin.argument_set not null,
    last_modified_arguments_at timestamptz not null,
    metadata merlin.activity_directive_metadata_set,

   anchor_id integer default null,
   anchored_to_start boolean default true not null,

    primary key (id, snapshot_id)
);

comment on table merlin.plan_snapshot_activities is e''
  'A record of the state of an activity at the time a snapshot was taken.';
