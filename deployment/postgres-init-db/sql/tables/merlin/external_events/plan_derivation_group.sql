create table merlin.plan_derivation_group (
    plan_id integer not null,
    derivation_group_name text not null,
    last_acknowledged_at timestamp with time zone default now() not null,
    acknowledged boolean not null default true,

    constraint plan_derivation_group_pkey
      primary key (plan_id, derivation_group_name),
    constraint pdg_plan_exists
      foreign key (plan_id)
      references merlin.plan(id)
      on delete cascade,
    constraint pdg_derivation_group_exists
      foreign key (derivation_group_name)
      references merlin.derivation_group(name)
      on update cascade
      on delete restrict
);

comment on table merlin.plan_derivation_group is e''
  'Links externally imported event sources & plans.\n'
  'Additionally, tracks the last time a plan owner/contributor(s) have acknowledged additions to the derivation group.\n';

comment on column merlin.plan_derivation_group.plan_id is e''
  'The plan with which the derivation group is associated.';
comment on column merlin.plan_derivation_group.derivation_group_name is e''
  'The derivation group being associated with the plan.';
comment on column merlin.plan_derivation_group.last_acknowledged_at is e''
  'The time at which changes to the derivation group were last acknowledged.';

-- update last_acknowledged whenever acknowledged is set to true
create function merlin.pdg_update_ack_at()
  returns trigger
  language plpgsql as $$
begin
  if new.acknowledged = true then
    new.last_acknowledged_at = now();
  end if;
  return new;
end;
$$;

create trigger pdg_update_ack_at
before update on merlin.plan_derivation_group
  for each row execute function merlin.pdg_update_ack_at();
