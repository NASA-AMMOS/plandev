alter table merlin.mission_model
  add column default_view_id integer default null,

  add foreign key (default_view_id)
    references ui.view (id)
    on delete set null;

comment on column merlin.mission_model.default_view_id is e''
  'The ID of an option default view for the mission model.';

call migrations.mark_migration_applied('4');
