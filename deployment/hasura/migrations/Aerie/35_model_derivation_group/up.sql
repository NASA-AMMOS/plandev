create table merlin.model_derivation_group (
    model_id integer not null,
    derivation_group_name text not null,

    constraint model_derivation_group_pkey
      primary key (model_id, derivation_group_name),
    constraint mdg_model_exists
      foreign key (model_id)
      references merlin.mission_model(id)
      on delete cascade,
    constraint mdg_derivation_group_exists
      foreign key (derivation_group_name)
      references merlin.derivation_group(name)
      on update cascade
      on delete restrict
);

comment on table merlin.model_derivation_group is e''
  'The default set of derivation groups that any new plans created using this model would have linked to them.';

comment on column merlin.model_derivation_group.model_id is e''
  'The model with which the derivation group is associated.';
comment on column merlin.model_derivation_group.derivation_group_name is e''
  'The derivation group being associated with the model.';


create function merlin.populate_derivation_groups_new_plan()
returns trigger
language plpgsql as $$
begin
  insert into merlin.plan_derivation_group (plan_id, derivation_group_name)
  select new.id, mdg.derivation_group_name
  from merlin.model_derivation_group mdg
  where mdg.model_id = new.model_id;
  return new;
end;
$$;

comment on function merlin.populate_derivation_groups_new_plan() is e''
'Populates the plan''s derivation group associations with the contents of its model''s derivation group associations.';

create trigger populate_derivation_groups_new_plan_trigger
after insert on merlin.plan
for each row
execute function merlin.populate_derivation_groups_new_plan();

call migrations.mark_migration_applied('35');
