create table merlin.simulation_dataset (
  id integer generated always as identity,
  simulation_id integer not null,
  dataset_id integer null,
  model_id integer null,

  -- This column may be removed in the future in favor of simulation_start_time
  offset_from_plan_start interval not null,

  -- Determinant entities
  plan_revision integer not null,
  model_revision integer not null,
  simulation_template_revision integer null,
  simulation_revision integer not null,

  -- Dependent entities
  dataset_revision integer null,

  -- Simulation Arguments
  arguments jsonb not null,
  simulation_start_time timestamptz not null,
  simulation_end_time timestamptz not null,

  -- Simulation state
  status util_functions.request_status not null default 'pending',
  reason jsonb null,
  canceled boolean not null default false,

  -- Additional Metadata
  requested_by text,
  requested_at timestamptz not null default now(),

  constraint simulation_dataset_synthetic_key
    primary key (id),
  constraint simulation_dataset_dataset_has_a_simulation
    unique (dataset_id),
  constraint simulation_dataset_references_simulation
    foreign key (simulation_id)
    references merlin.simulation
    on update cascade
    on delete cascade,
  constraint simulation_dataset_references_dataset
    foreign key (dataset_id)
    references merlin.dataset
    on update cascade
    on delete cascade,
  constraint simulation_dataset_requested_by_exists
    foreign key (requested_by) references permissions.users
    on update cascade
    on delete set null,
  constraint simulation_dataset_references_model
    foreign key (model_id)
    references merlin.mission_model
    on update cascade
    on delete set null,
  constraint start_before_end
    check (simulation_start_time <= simulation_end_time)
);

create index simulation_dataset_simulation_has_many_datasets
  on merlin.simulation_dataset (simulation_id);

comment on table merlin.simulation_dataset is e''
  'A description of the upstream simulation inputs that determined a given dataset.'
'\n'
  'A new row should be created by providing a simulation_id and offset_from_plan_start only '
  'as the remaining data will be filled in during the insertion';

comment on column merlin.simulation_dataset.simulation_id is e''
  'The simulation determining the contents of the associated dataset.';
comment on column merlin.simulation_dataset.dataset_id is e''
  'The dataset containing simulated results for the simulation. NULL if the dataset has not been constructed yet.';
comment on column merlin.simulation_dataset.model_id is e''
  'The model id used for this simulation.';
comment on column merlin.simulation_dataset.plan_revision is e''
  'The revision of the plan corresponding to the given revision of the dataset.';
comment on column merlin.simulation_dataset.model_revision is e''
  'The revision of the mission model corresponding to the given revision of the dataset.';
comment on column merlin.simulation_dataset.simulation_revision is e''
  'The revision of the simulation corresponding to the given revision of the dataset.';
comment on column merlin.simulation_dataset.dataset_revision is e''
  'The revision of the dataset corresponding to the given revisions of the input entities.';
comment on column merlin.simulation_dataset.status is e''
  'The status of the simulation for which the dataset is associated.';
comment on column merlin.simulation_dataset.reason is e''
  'The reason for failure in the event that simulation fails.';
comment on column merlin.simulation_dataset.canceled is e''
  'Whether the simulation has been marked as canceled.';
comment on column merlin.simulation_dataset.offset_from_plan_start is e''
  'The time to judge dataset items against relative to the plan start.'
'\n'
  'If the dataset as a whole begins one day before the planning period begins, '
  'then this column should contain the interval ''1 day ago''.';
comment on column merlin.simulation_dataset.requested_by is e''
  'The user who requested the simulation.';
comment on column merlin.simulation_dataset.requested_at is e''
  'When this simulation dataset was created.';

-- Dataset management triggers
-- These triggers create and delete datasets along with the insert/delete of a simulation_dataset

create function merlin.set_revisions_and_initialize_dataset_on_insert()
returns trigger
security definer
language plpgsql as $$
declare
  simulation_ref merlin.simulation;
  plan_ref merlin.plan;
  model_ref merlin.mission_model;
  template_ref merlin.simulation_template;
  dataset_ref merlin.dataset;
begin
  -- Set the revisions
  select into simulation_ref * from merlin.simulation where id = new.simulation_id;
  select into plan_ref * from merlin.plan where id = simulation_ref.plan_id;
  select into template_ref * from merlin.simulation_template where id = simulation_ref.simulation_template_id;
  select into model_ref * from merlin.mission_model where id = plan_ref.model_id;
  new.model_revision = model_ref.revision;
  new.plan_revision = plan_ref.revision;
  new.simulation_template_revision = template_ref.revision;
  new.simulation_revision = simulation_ref.revision;
  new.model_id = plan_ref.model_id;

  -- Create the dataset
  insert into merlin.dataset
  default values
  returning * into dataset_ref;
  new.dataset_id = dataset_ref.id;
  new.dataset_revision = dataset_ref.revision;
return new;
end$$;

create trigger set_revisions_and_initialize_dataset_on_insert_trigger
  before insert on merlin.simulation_dataset
  for each row
  execute function merlin.set_revisions_and_initialize_dataset_on_insert();

create function merlin.delete_dataset_on_delete()
returns trigger
security definer
language plpgsql as $$begin
  delete from merlin.dataset
  where id = old.dataset_id;
return old;
end$$;

create trigger delete_dataset_on_delete_trigger
  after delete on merlin.simulation_dataset
  for each row
  execute function merlin.delete_dataset_on_delete();

-- Simulation dataset NOTIFY triggers
-- These triggers NOTIFY LISTEN(ing) merlin worker clients of pending simulation requests

create function merlin.notify_simulation_workers()
returns trigger
security definer
language plpgsql as $$
declare
  simulation_ref merlin.simulation;
begin
  select into simulation_ref * from merlin.simulation where id = new.simulation_id;

  perform (
    with payload(model_revision,
                 plan_revision,
                 simulation_revision,
                 simulation_template_revision,
                 dataset_id,
                 simulation_id,
                 plan_id) as
    (
      select NEW.model_revision,
             NEW.plan_revision,
             NEW.simulation_revision,
             NEW.simulation_template_revision,
             NEW.dataset_id,
             NEW.simulation_id,
             simulation_ref.plan_id
    )
    select pg_notify('simulation_notification', json_strip_nulls(row_to_json(payload))::text)
    from payload
  );
  return null;
end$$;

create trigger notify_simulation_workers
  after insert on merlin.simulation_dataset
  for each row
  execute function merlin.notify_simulation_workers();

create function merlin.notify_simulation_workers_cancel()
returns trigger
security definer
language plpgsql as $$
begin
  perform pg_notify('simulation_cancel', '' || new.dataset_id);
  return null;
end
$$;

create trigger notify_simulation_workers_cancel
after update of canceled on merlin.simulation_dataset
for each row
when ((old.status != 'success' or old.status != 'failed') and new.canceled)
execute function merlin.notify_simulation_workers_cancel();

create function merlin.update_offset_from_plan_start()
returns trigger
security invoker
language plpgsql as $$
declare
  plan_start timestamptz;
begin
  select p.start_time
  from merlin.simulation s, merlin.plan p
  where s.plan_id = p.id
    and new.simulation_id = s.id
  into plan_start;

  new.offset_from_plan_start = new.simulation_start_time - plan_start;
  return new;
end
$$;

create trigger update_offset_from_plan_start_trigger
before insert or update on merlin.simulation_dataset
for each row
execute function merlin.update_offset_from_plan_start();
