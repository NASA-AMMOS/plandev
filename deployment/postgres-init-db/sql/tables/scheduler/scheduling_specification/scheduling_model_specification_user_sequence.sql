create table scheduler.scheduling_model_specification_user_sequence(
  model_id integer not null,
  user_sequence_id integer not null,
  user_sequence_revision integer,

  primary key (model_id, user_sequence_id),
  foreign key (user_sequence_id)
    references sequencing.user_sequence_metadata
    on update cascade
    on delete restrict,
  foreign key (user_sequence_id, user_sequence_revision)
    references sequencing.user_sequence
    on update cascade
    on delete restrict,
  foreign key (model_id)
    references merlin.mission_model
    on update cascade
    on delete cascade
);

comment on table scheduler.scheduling_model_specification_user_sequence is e''
'The set of sequences that all plans using the model should include in their scheduling specification.';
comment on column scheduler.scheduling_model_specification_user_sequence.model_id is e''
'The model which this specification is for. Half of the primary key.';
comment on column scheduler.scheduling_model_specification_user_sequence.user_sequence_id is e''
'The id of a specific sequence. Half of the primary key.';
