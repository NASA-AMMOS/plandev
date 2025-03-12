-- introduce sequence templates
create table sequencing.sequence_template (
  id integer generated always as identity,
  name text not null,

  model_id integer not null,
  parcel_id integer not null,
  template_definition text not null,
  activity_type text not null,
  language text not null,
  owner text,

    constraint sequence_template_pkey primary key (id),
    constraint activity_type foreign key (activity_type, model_id)
        references merlin.activity_type (name, model_id) match simple
        on update cascade
        on delete cascade
        NOT VALID,
    constraint "model_id -> merlin.mission_model.id" foreign key (model_id)
        references merlin.mission_model (id) match simple
        on update cascade
        on delete cascade,
    constraint "parcel_id -> sequencing.parcel.id" foreign key (parcel_id)
        references sequencing.parcel (id) match simple
        on update cascade
        on delete cascade,

  constraint only_one_template_per_model_activity_type
      unique (model_id, activity_type)
);


-- introduce sequence filters
create table sequencing.sequence_filter (
  id integer generated always as identity,
  filter jsonb not null default '{}'::jsonb,
  model_id integer not null,
  name text,

  constraint sequence_filter_primary_key
  primary key (id),

  foreign key (model_id)
    references merlin.mission_model
    on update cascade
    on delete cascade
);


-- introduce a table to hold the result of expanded templates, in a separate table from expanded sequences because
--    this result is in text, not necessarily jsonb.
create table sequencing.expanded_templates (
  id integer generated always as identity,

  seq_id text not null,
  simulation_dataset_id int not null,
  expanded_template text not null,

  created_at timestamptz not null default now(),

  constraint expanded_template_primary_key
    primary key (id),

  constraint expanded_template_to_sim_run
    foreign key (simulation_dataset_id)
      references merlin.simulation_dataset
      on delete cascade,

  constraint expanded_template_to_sequence
      foreign key (seq_id, simulation_dataset_id)
        references sequencing.sequence
        on delete cascade
);

comment on table sequencing.expanded_templates is e''
  'A cache of sequences that have already been expanded.';


call migrations.mark_migration_applied('14');
