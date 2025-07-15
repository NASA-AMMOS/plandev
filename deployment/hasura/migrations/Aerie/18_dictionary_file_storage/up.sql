alter table sequencing.command_dictionary
  add column dictionary_file_path text default null;

comment on column sequencing.command_dictionary.dictionary_file_path is e''
  'The location of the command dictionary file on the filesystem.';

alter table sequencing.channel_dictionary
  add column dictionary_file_path text default null;

comment on column sequencing.channel_dictionary.dictionary_file_path is e''
  'The location of the channel dictionary file on the filesystem.';

alter table sequencing.parameter_dictionary
  add column dictionary_file_path text default null;

comment on column sequencing.parameter_dictionary.dictionary_file_path is e''
  'The location of the parameter dictionary file on the filesystem.';

call migrations.mark_migration_applied('18');
