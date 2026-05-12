insert into ui.file_extension_content_type values ('.aerie', 'Metadata'::ui.supported_content_types);
delete from ui.file_extension_content_type where file_extension = '.meta.seqdev';

call migrations.mark_migration_rolled_back(32);
