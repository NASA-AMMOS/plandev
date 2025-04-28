create table ui.file_extension_content_type(
  file_extension text not null,
  content_type ui.supported_content_types not null,

  primary key (file_extension)
);

comment on table ui.file_extension_content_type is e''
  'An association table between file extensions and their content type.'
  'Used for informing the UI how to render files based on the extension.';

-- Initialize data in the table
insert into ui.file_extension_content_type(file_extension, content_type)
values ('.txt', 'Text'),
       ('.bin', 'Binary'),
       ('.json', 'JSON'),
       ('.aerie', 'Metadata'),
       ('.seq', 'Sequence'),
       ('.seqN.txt', 'Sequence'),
       ('.seq.json', 'Sequence'),
       ('.rml', 'Sequence'),
       ('.vml', 'Sequence'),
       ('.sasf', 'Sequence'),
       ('.satf', 'Sequence');
