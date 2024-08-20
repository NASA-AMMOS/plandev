create table scheduler.scheduling_goal_analysis_created_activities (
  analysis_id integer not null,
  goal_invocation_id integer not null,
  activity_id integer not null,

  constraint created_activities_primary_key
    primary key (analysis_id, goal_invocation_id, activity_id),
  constraint created_activities_references_scheduling_request
    foreign key (analysis_id)
      references scheduler.scheduling_request
      on update cascade
      on delete cascade,
  constraint created_activities_references_scheduling_goal_analysis
    foreign key (analysis_id, goal_invocation_id)
      references scheduler.scheduling_goal_analysis
      on update cascade
      on delete cascade
);

comment on table scheduler.scheduling_goal_analysis_created_activities is e''
  'The activity instances created by a scheduling run to satisfy a goal.';
comment on column scheduler.scheduling_goal_analysis_created_activities.analysis_id is e''
  'The associated analysis ID.';
comment on column scheduler.scheduling_goal_analysis_created_activities.goal_invocation_id is e''
  'The associated goal invocation ID from the scheduling specification.';
comment on column scheduler.scheduling_goal_analysis_created_activities.activity_id is e''
  'The ID of an activity instance created to satisfy the associated goal.';
