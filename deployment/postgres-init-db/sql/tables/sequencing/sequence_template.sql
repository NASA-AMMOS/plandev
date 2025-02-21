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
        on update no action
        on delete no action
        NOT VALID,
    constraint "model_id -> merlin.mission_model.id" foreign key (model_id)
        references merlin.mission_model (id) match simple
        on update no action
        on delete no action,
    constraint "parcel_id -> sequencing.parcel.id" foreign key (parcel_id)
        references sequencing.parcel (id) match simple
        on update no action
        on delete no action,

  constraint only_one_template_per_model_activity_type
      unique (model_id, activity_type)
)
