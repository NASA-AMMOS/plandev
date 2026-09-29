create table merlin.plan_import_request (
  id integer generated always as identity,

  status merlin.plan_import_request_status not null,
  reason jsonb null,

  requester text,
  requested_at timestamptz default now() not null,

  model_id integer null,
  plan_id integer null,

  primary key (id),
  constraint plan_import_requester_exists
    foreign key (requester)
      references permissions.users
      on update cascade
      on delete set null,
  constraint plan_import_request_model_exists
    foreign key (model_id)
      references merlin.mission_model
      on update cascade
      on delete set null,
  constraint plan_import_request_plan_exists
    foreign key (plan_id)
      references merlin.plan
      on update cascade
      on delete set null
);

comment on table merlin.plan_import_request is e''
'A request for a Read Only Plan to be imported from a plan.json file';

comment on column merlin.plan_import_request.id is e''
'The synthetic identifier for this request.';
comment on column merlin.plan_import_request.status is e''
'The current status of this request.';
comment on column merlin.plan_import_request.reason is e''
'The reason for failure, in the event this import request fails.';
comment on column merlin.plan_import_request.requester is e''
'The user who made this request.';
comment on column merlin.plan_import_request.requested_at is e''
'The time at which the request was made.';
comment on column merlin.plan_import_request.model_id is e''
'The id of the model created by this request.';
comment on column merlin.plan_import_request.plan_id is e''
'The id of the plan created by this request.';
