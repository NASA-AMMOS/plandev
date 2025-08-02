create table sequencing.command_dictionary (
  id integer generated always as identity,

  dictionary_path text not null,
  dictionary_file_path text default null,
  mission text not null,
  version text not null,
  parsed_json jsonb not null default '{}', -- Todo: remove and create a endpoint for the frontend to use the path

  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),

  constraint command_dictionary_synthetic_key
    primary key (id),
  constraint command_dictionary_natural_key
    unique (mission,version)
);

comment on table sequencing.command_dictionary is e''
  'A Command Dictionary for a mission.';
comment on column sequencing.command_dictionary.id is e''
  'The synthetic identifier for this command dictionary.';
comment on column sequencing.command_dictionary.dictionary_path is e''
  'The location of command dictionary types (.ts) on the filesystem';
comment on column sequencing.command_dictionary.dictionary_file_path is e''
  'The location of the command dictionary file on the filesystem.';
comment on column sequencing.command_dictionary.mission is e''
  'A human-meaningful identifier for the mission described by the command dictionary';
comment on column sequencing.command_dictionary.version is e''
  'A human-meaningful version qualifier.';
comment on column sequencing.command_dictionary.parsed_json is e''
  'The XML that has been parsed and converted to JSON';
comment on constraint command_dictionary_natural_key on sequencing.command_dictionary is e''
  'There can only be one command dictionary of a given version for a given mission.';

create trigger set_timestamp
before update on sequencing.command_dictionary
for each row
execute function util_functions.set_updated_at();
