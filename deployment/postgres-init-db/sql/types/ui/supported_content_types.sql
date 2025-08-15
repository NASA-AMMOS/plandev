create type ui.supported_content_types as enum('Text', 'Binary', 'JSON', 'Sequence', 'Metadata');

comment on type ui.supported_content_types is e''
  'The set of content types that the Aerie UI supports.';
