drop table sequencing.expanded_templates;
drop table sequencing.sequence_filter;
drop table sequencing.sequence_template;

call migrations.mark_migration_rolled_back('14');
