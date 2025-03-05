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
