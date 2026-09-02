insert into ui.file_extension_content_type values ('.meta.seqdev', 'Metadata'::ui.supported_content_types);
delete from ui.file_extension_content_type where file_extension = '.aerie';

call migrations.mark_migration_applied(32);
