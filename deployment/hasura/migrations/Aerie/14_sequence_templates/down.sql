drop table sequencing.expanded_templates cascade;
drop table sequencing.sequence_filter cascade;
drop trigger ensure_language_match on sequencing.sequence_template;
drop function sequencing.check_language_sameness cascade;
drop table sequencing.sequence_template cascade;

call migrations.mark_migration_rolled_back('14');
