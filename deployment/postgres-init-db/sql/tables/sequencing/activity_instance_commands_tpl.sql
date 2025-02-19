create table sequencing.activity_instance_commands_tpl
(
  id integer generated always as identity,
  activity_instance_id integer not null,
  commands jsonb,
  errors jsonb not null not null,
  template_expansion_run_id integer not null,
  CONSTRAINT activity_instance_commands_tpl_synthetic_key PRIMARY KEY (id),
  CONSTRAINT activity_instance_commands_expansion_run_tpl_id_fkey FOREIGN KEY (template_expansion_run_id)
      REFERENCES sequencing.template_expansion_run (id) MATCH SIMPLE
      ON UPDATE NO ACTION
      ON DELETE NO ACTION
)
