alter table sequencing.command_dictionary
  drop column dictionary_file_path;

alter table sequencing.channel_dictionary
  drop column dictionary_file_path;

alter table sequencing.parameter_dictionary
  drop column dictionary_file_path;

call migrations.mark_migration_rolled_back('18');
