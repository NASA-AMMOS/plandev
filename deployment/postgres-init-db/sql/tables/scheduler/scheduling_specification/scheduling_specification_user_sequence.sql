create table scheduler.scheduling_specification_user_sequence (
  specification_id integer not null,
  user_sequence_id integer not null,
  user_sequence_revision integer, -- latest is NULL
  enabled boolean default true,

  constraint scheduling_specification_user_sequences_primary_key
    primary key (specification_id, user_sequence_id),
  constraint scheduling_specification_user_sequences_specification_exists
    foreign key (specification_id)
      references scheduler.scheduling_specification
      on update cascade
      on delete cascade,
  constraint scheduling_specification_user_sequence_metadata_exists
    foreign key (user_sequence_id)
      references sequencing.user_sequence_metadata
      on update cascade
      on delete restrict,
  constraint scheduling_specification_user_sequence_exists
    foreign key (user_sequence_id, user_sequence_revision)
      references sequencing.user_sequence
      on update cascade
      on delete restrict
);

comment on table scheduler.scheduling_specification_user_sequence is e''
  'The set of sequence templates to be used on a given plan.';
comment on column scheduler.scheduling_specification_user_sequence.specification_id is e''
  'The plan scheduling specification which this sequence is on. Half of the primary key.';
comment on column scheduler.scheduling_specification_user_sequence.user_sequence_id is e''
  'The ID of a specific sequence in the specification. Half of the primary key.';
comment on column scheduler.scheduling_specification_user_sequence.user_sequence_revision is e''
  'The version of the sequence definition to use. Leave NULL to use the latest version.';
comment on column scheduler.scheduling_specification_user_sequence.enabled is e''
  'Whether to use a given condition. Defaults to TRUE.';

-- create function scheduler.increment_spec_revision_on_conditions_spec_update()
--   returns trigger
--   security definer
-- language plpgsql as $$
-- begin
--   update scheduler.scheduling_specification
--   set revision = revision + 1
--   where id = new.specification_id;
--   return new;
-- end;
-- $$;

-- create trigger increment_revision_on_condition_update
--   before insert or update on scheduler.scheduling_specification_conditions
--   for each row
--   execute function scheduler.increment_spec_revision_on_conditions_spec_update();

-- create function scheduler.increment_spec_revision_on_conditions_spec_delete()
--   returns trigger
--   security definer
-- language plpgsql as $$
-- begin
--   update scheduler.scheduling_specification
--   set revision = revision + 1
--   where id = new.specification_id;
--   return old;
-- end;
-- $$;

-- create trigger increment_revision_on_condition_delete
--   before delete on scheduler.scheduling_specification_conditions
--   for each row
--   execute function scheduler.increment_spec_revision_on_conditions_spec_delete();
