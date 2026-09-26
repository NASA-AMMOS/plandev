create table merlin.simulation (
  id integer generated always as identity,
  revision integer not null default 0,

  simulation_template_id integer null,
  plan_id integer not null,
  arguments merlin.argument_set not null,

  simulation_start_time timestamptz not null,
  simulation_end_time timestamptz not null,

  constraint simulation_synthetic_key
    primary key (id),
  constraint simulation_has_simulation_template
    foreign key (simulation_template_id)
    references merlin.simulation_template
    on update cascade
    on delete set null,
  constraint simulation_owned_by_plan
    foreign key (plan_id)
    references merlin.plan
    on update cascade
    on delete cascade,
  constraint one_simulation_per_plan
    unique(plan_id),
  constraint simulation_end_after_simulation_start
    check (simulation_start_time <= simulation_end_time)
);

comment on table merlin.simulation is e''
  'A specification for simulating an activity plan.';

comment on column merlin.simulation.id is e''
  'The synthetic identifier for this simulation.';
comment on column merlin.simulation.revision is e''
  'A monotonic clock that ticks for every change to this simulation.';
comment on column merlin.simulation.simulation_template_id is e''
  'A simulation template specification to inherit.';
comment on column merlin.simulation.plan_id is e''
  'The plan whose contents drive this simulation.';
comment on column merlin.simulation.arguments is e''
  'The set of arguments to this simulation, corresponding to the parameters of the associated mission model.';

create trigger increment_revision_for_update_simulation_trigger
before update on merlin.simulation
for each row
when (pg_trigger_depth() < 1)
execute function util_functions.increment_revision_update();

create trigger check_locked_on_update_trigger
  before update on merlin.simulation
  for each row
execute procedure util_functions.check_plan_locked_readonly_insert_update();
