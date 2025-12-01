--------------------------------------
---- Create the Channel Dictionary Table
--------------------------------------

create table sequencing.channel_dictionary (
 id integer generated always as identity,

 dictionary_path text not null,
 mission text not null,
 version text not null,
 parsed_json jsonb not null default '{}', -- Todo: remove and create a endpoint for the frontend to use the path

 created_at timestamptz not null default now(),
 updated_at timestamptz not null default now(),

 constraint channel_dictionary_synthetic_key
   primary key (id),
 constraint channel_dictionary_natural_key
   unique (mission,version)
);

comment on table sequencing.channel_dictionary is e''
  'A Channel Dictionary for a mission.';
comment on column sequencing.channel_dictionary.id is e''
  'The synthetic identifier for this channel dictionary.';
comment on column sequencing.channel_dictionary.dictionary_path is e''
  'The location of channel dictionary json on the filesystem';
comment on column sequencing.channel_dictionary.mission is e''
  'A human-meaningful identifier for the mission described by the channel dictionary';
comment on column sequencing.channel_dictionary.version is e''
  'A human-meaningful version qualifier.';
comment on column sequencing.channel_dictionary.parsed_json is e''
  'The XML that has been parsed and converted to JSON';
comment on constraint channel_dictionary_natural_key on sequencing.channel_dictionary is e''
  'There can only be one channel dictionary of a given version for a given mission.';

create trigger set_timestamp
  before update on sequencing.channel_dictionary
  for each row
execute function util_functions.set_updated_at();

--------------------------------------
---- Create the Seq Adaptation Table
--------------------------------------
create table sequencing.sequence_adaptation (
  id integer generated always as identity,
  adaptation text not null,
  created_at timestamptz not null default now(),
  owner text,
  updated_at timestamptz not null default now(),
  updated_by text,

  constraint sequence_adaptation_synthetic_key
    primary key (id),
  foreign key (owner)
    references permissions.users
    on update cascade
    on delete set null,
  foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

comment on table sequencing.sequence_adaptation is e''
  'A custom adaptation used to overwrite variable and linting rules for the sequence editor';
comment on column sequencing.sequence_adaptation.adaptation is e''
  'The sequencing adaptation code.';

create trigger set_timestamp
  before update on sequencing.sequence_adaptation
  for each row
execute function util_functions.set_updated_at();


--------------------------------------
---- Create the Parcel Table
--------------------------------------
create table sequencing.parcel (
 id integer generated always as identity,

 name text not null,

 command_dictionary_id integer not null,
 channel_dictionary_id integer default null,
 sequence_adaptation_id integer default null,

 created_at timestamptz not null default now(),
 owner text,
 updated_at timestamptz not null default now(),
 updated_by text,

 constraint parcel_synthetic_key
   primary key (id),

 foreign key (channel_dictionary_id)
   references sequencing.channel_dictionary (id)
   on delete set null,
 foreign key (command_dictionary_id)
   references sequencing.command_dictionary (id)
   on delete cascade,
 foreign key (sequence_adaptation_id)
   references sequencing.sequence_adaptation (id)
   on delete set null,
 foreign key (owner)
   references permissions.users
   on update cascade
   on delete set null,
  foreign key (updated_by)
   references permissions.users
   on update cascade
   on delete set null
);

comment on table sequencing.parcel is e''
  'A bundled containing dictionaries and a sequence adaptation file.';
comment on column sequencing.parcel.id is e''
  'The synthetic identifier for this parcel.';
comment on column sequencing.parcel.name is e''
  'The name of the parcel.';
comment on column sequencing.parcel.channel_dictionary_id is e''
  'The identifier for the channel dictionary.';
comment on column sequencing.parcel.command_dictionary_id is e''
  'The identifier for the command dictionary.';
comment on column sequencing.parcel.sequence_adaptation_id is e''
  'The identifier for the sequencing adaptation file.';

create trigger set_timestamp
  before update on sequencing.parcel
  for each row
execute function util_functions.set_updated_at();

--- populate table with data
insert into sequencing.parcel (name, command_dictionary_id, owner, updated_by)
select
    'Parcel ' || cd.id as name,
    cd.id,
    'Aerie Legacy',
    'Aerie Legacy'
from sequencing.command_dictionary cd;

----------------------------------------
------ Create Parameter Dictionary Table
-----------------------------------------

create table sequencing.parameter_dictionary (
   id integer generated always as identity,

   dictionary_path text not null,
   mission text not null,
   version text not null,
   parsed_json jsonb not null default '{}', -- Todo: remove and create a endpoint for the frontend to use the path

   created_at timestamptz not null default now(),
   updated_at timestamptz not null default now(),

   constraint parameter_dictionary_synthetic_key
     primary key (id),
   constraint parameter_dictionary_natural_key
     unique (mission,version)
);

comment on table sequencing.parameter_dictionary is e''
  'A Parameter Dictionary for a mission.';
comment on column sequencing.parameter_dictionary.id is e''
  'The synthetic identifier for this parameter dictionary.';
comment on column sequencing.parameter_dictionary.dictionary_path is e''
  'The location of parameter dictionary json on the filesystem';
comment on column sequencing.parameter_dictionary.mission is e''
  'A human-meaningful identifier for the mission described by the parameter dictionary';
comment on column sequencing.parameter_dictionary.version is e''
  'A human-meaningful version qualifier.';
comment on column sequencing.parameter_dictionary.parsed_json is e''
  'The XML that has been parsed and converted to JSON';
comment on constraint parameter_dictionary_natural_key on sequencing.parameter_dictionary is e''
  'There can only be one dictionary of a given version for a given mission.';

create trigger set_timestamp
  before update on sequencing.parameter_dictionary
  for each row
execute function util_functions.set_updated_at();

-----------------------------------------
------ Create Parcel to Parameter Table
-----------------------------------------

create table sequencing.parcel_to_parameter_dictionary (

 parcel_id integer not null,
 parameter_dictionary_id integer not null,

 foreign key (parcel_id)
   references sequencing.parcel (id)
   on delete cascade,
 foreign key (parameter_dictionary_id)
   references sequencing.parameter_dictionary (id)
   on delete cascade
);

comment on table sequencing.parcel_to_parameter_dictionary is e''
  'Parcels can contain multiple parameter dictionaries so this table keeps track of references between the two.';

--------------------------------------
---- Modify the User Sequence Table
--------------------------------------
alter table sequencing.user_sequence
  add column seq_json jsonb,
  add column parcel_id integer,

  add foreign key (parcel_id)
    references sequencing.parcel (id)
    on delete cascade;

comment on column sequencing.user_sequence.parcel_id is e''
  'Parcel the user sequence was created with.';
comment on column sequencing.user_sequence.seq_json is e''
  'The SeqJson representation of the user sequence.';

--- Data Migration

update sequencing.user_sequence us
set parcel_id = p.id
from sequencing.parcel p
where us.authoring_command_dict_id = p.command_dictionary_id;

alter table sequencing.user_sequence
  alter column parcel_id set not null;
---

alter table sequencing.user_sequence
  drop constraint user_sequence_authoring_command_dict_id_fkey,
  drop authoring_command_dict_id;


--------------------------------------
---- Modify the Expansion Set Table
--------------------------------------
alter table sequencing.expansion_set
  add column parcel_id integer;

comment on column sequencing.expansion_set.parcel_id is e''
  'The ID of a parcel';

--- Data Migration
update sequencing.expansion_set es
set parcel_id = p.id
from sequencing.parcel p
where es.command_dict_id = p.command_dictionary_id;

alter table sequencing.expansion_set
  alter column parcel_id set not null;
---

alter table sequencing.expansion_set
  add constraint expansion_set_unique_name_per_parcel_and_model
    unique (mission_model_id, parcel_id, name),

  add foreign key (parcel_id)
    references sequencing.parcel (id)
    on delete cascade,

  drop constraint expansion_set_unique_name_per_dict_and_model,
  drop constraint expansion_set_command_dict_id_fkey;

drop view sequencing.rule_expansion_set_view;

create or replace  view sequencing.rule_expansion_set_view as
select str.rule_id,
       set.id,
       set.name,
       set.owner,
       set.description,
       set.parcel_id,
       set.mission_model_id,
       set.created_at,
       set.updated_at,
       set.updated_by
from sequencing.expansion_set_to_rule str left join sequencing.expansion_set set
                                                    on str.set_id = set.id;


alter table sequencing.expansion_set
  drop column command_dict_id;

--------------------------------------
---- Modify the Expansion Rule Table
--------------------------------------

alter table sequencing.expansion_rule
  add column parcel_id integer,
  add foreign key (parcel_id)
    references sequencing.parcel (id)
    on delete set null;

comment on column sequencing.expansion_rule.parcel_id is e''
  'The id of the parcel to be used for authoring of this expansion.';

--- Data Migration

update sequencing.expansion_rule sr
set parcel_id = p.id
from sequencing.parcel p
where p.command_dictionary_id = sr.authoring_command_dict_id;

---

alter table sequencing.expansion_rule
  add constraint expansion_rule_unique_name_per_parcel_and_model
    unique (authoring_mission_model_id, parcel_id, name),

  drop constraint expansion_rule_unique_name_per_dict_and_model,
  drop constraint expansion_rule_authoring_command_dict_id_fkey;

drop view sequencing.expansion_set_rule_view;

create or replace view sequencing.expansion_set_rule_view as
select str.set_id,
       rule.id,
       rule.activity_type,
       rule.expansion_logic,
       rule.parcel_id,
       rule.authoring_mission_model_id,
       rule.created_at,
       rule.updated_at,
       rule.name,
       rule.owner,
       rule.updated_by,
       rule.description
from sequencing.expansion_set_to_rule str
left join sequencing.expansion_rule rule
on str.rule_id = rule.id;

alter table sequencing.expansion_rule
  drop column authoring_command_dict_id;


---------------------------------------
--- Modify the Command Dictionary Table
---------------------------------------

alter table sequencing.command_dictionary
  add column updated_at timestamptz not null default now();

alter table sequencing.command_dictionary
  rename column command_types_typescript_path to dictionary_path;

create trigger set_timestamp
  before update on sequencing.command_dictionary
  for each row
execute function util_functions.set_updated_at();

------------------------------------
--- Modify Expanded Sequences Table
------------------------------------

alter table sequencing.expanded_sequences
  drop column edsl_string;

call migrations.mark_migration_applied('3');


