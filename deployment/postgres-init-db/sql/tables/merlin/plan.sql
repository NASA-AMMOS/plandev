create table merlin.plan (
  id integer generated always as identity check ( id > 0 ),
  revision integer not null default 0,

  name text not null,
  model_id integer null,
  duration interval not null,

  start_time timestamptz not null,
  parent_id integer
    references merlin.plan
    on update cascade,

  is_locked boolean not null default false,

  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),

  owner text,
  updated_by text,
  description text,

  constraint plan_synthetic_key
    primary key (id),
  constraint plan_natural_key
    unique (name),
  constraint plan_uses_model
    foreign key (model_id)
    references merlin.mission_model
    on update cascade
    on delete set null,
  constraint plan_owner_exists
    foreign key (owner)
    references permissions.users
    on update cascade
    on delete set null,
  constraint plan_updated_by_exists
    foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

create index plan_model_id_index on merlin.plan (model_id);

comment on table merlin.plan is e''
  'A set of activities scheduled against a mission model.';

comment on column merlin.plan.id is e''
  'The synthetic identifier for this plan.';
comment on column merlin.plan.revision is e''
  'A monotonic clock that ticks for every change to this plan.';
comment on column merlin.plan.name is e''
  'A human-readable name for this plan. Unique amongst all plans.';
comment on column merlin.plan.model_id is e''
  'The mission model used to simulate and validate the plan.'
'\n'
  'May be NULL if the mission model the plan references has been deleted.';
comment on column merlin.plan.duration is e''
  'The duration over which this plan extends.';
comment on column merlin.plan.start_time is e''
  'The time at which the plan''s effective span begins.';
comment on column merlin.plan.parent_id is e''
  'The plan id of the parent of this plan. May be NULL if this plan does not have a parent.';
comment on column merlin.plan.is_locked is e''
  'A boolean representing whether this plan can be deleted and if changes can happen to the activities of this plan.';
comment on column merlin.plan.created_at is e''
  'The time at which this plan was created.';
comment on column merlin.plan.updated_at is e''
  'The time at which this plan was last updated.';
comment on column merlin.plan.owner is e''
  'The user who owns the plan.';
comment on column merlin.plan.updated_by is e''
  'The user who last updated the plan.';
comment on column merlin.plan.description is e''
  'A human-readable description for this plan and its contents.';

-- Insert Triggers

create function merlin.create_simulation_row_for_new_plan()
returns trigger
security definer
language plpgsql as $$begin
  insert into merlin.simulation (revision, simulation_template_id, plan_id, arguments, simulation_start_time, simulation_end_time)
  values (0, null, new.id, '{}', new.start_time, new.start_time+new.duration);
  return new;
end
$$;

create trigger simulation_row_for_new_plan_trigger
after insert on merlin.plan
for each row
execute function merlin.create_simulation_row_for_new_plan();

create function merlin.populate_constraint_spec_new_plan()
returns trigger
language plpgsql as $$
begin
  insert into merlin.constraint_specification (plan_id, constraint_id, constraint_revision, arguments, priority)
  select new.id, cms.constraint_id, cms.constraint_revision, cms.arguments, cms.priority
  from merlin.constraint_model_specification cms
  where cms.model_id = new.model_id
  order by priority;
  return new;
end;
$$;

comment on function merlin.populate_constraint_spec_new_plan() is e''
'Populates the plan''s constraint specification with the contents of its model''s specification.';

create trigger populate_constraint_spec_new_plan_trigger
after insert on merlin.plan
for each row
execute function merlin.populate_constraint_spec_new_plan();

-- Insert or Update Triggers

create trigger set_timestamp
before update or insert on merlin.plan
for each row
execute function util_functions.set_updated_at();

create trigger check_plan_duration_is_nonnegative_trigger
before insert or update on merlin.plan
for each row
when (new.duration < '0')
execute function util_functions.raise_duration_is_negative();

-- Update Triggers

create trigger increment_revision_plan_update
before update on merlin.plan
for each row
when (pg_trigger_depth() < 1)
execute function util_functions.increment_revision_update();

-- Delete Triggers

create function merlin.cleanup_on_delete()
  returns trigger
  language plpgsql as $$
begin
  -- prevent deletion if the plan is locked
  if old.is_locked then
    raise exception 'Cannot delete locked plan.';
  end if;

  -- withdraw pending rqs
  update merlin.merge_request
  set status='withdrawn'
  where plan_id_receiving_changes = old.id
    and status = 'pending';

  -- have the children be 'adopted' by this plan's parent
  update merlin.plan
  set parent_id = old.parent_id
  where
    parent_id = old.id;
  return old;
end
$$;

create trigger cleanup_on_delete_trigger
  before delete on merlin.plan
  for each row
execute function merlin.cleanup_on_delete();
