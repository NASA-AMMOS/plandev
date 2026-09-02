
------------------------------------
--- Modify Expanded Sequences Table
------------------------------------
alter table sequencing.expanded_sequences
  add column edsl_string text not null default '{}';

alter table sequencing.expanded_sequences
  alter column edsl_string drop default;

------------------------------------------
---- Modify the Command Dictionary Table
------------------------------------------
drop trigger set_timestamp on sequencing.command_dictionary;

alter table sequencing.command_dictionary
  rename column dictionary_path to command_types_typescript_path;

alter table sequencing.command_dictionary
  drop column updated_at;

--------------------------------------
---- Modify the Expansion Rule Table
--------------------------------------
alter table sequencing.expansion_rule
  add column authoring_command_dict_id integer;

comment on column sequencing.expansion_rule.authoring_command_dict_id is e''
  'The id of the command dictionary to be used for authoring of this expansion.';

--- Data Migration
update sequencing.expansion_rule er
set authoring_command_dict_id = p.command_dictionary_id
from sequencing.parcel p
where er.parcel_id = p.id;

---

alter table sequencing.expansion_rule
  add constraint expansion_rule_unique_name_per_dict_and_model
    unique (authoring_mission_model_id, authoring_command_dict_id, name),

  add foreign key (authoring_command_dict_id)
    references sequencing.command_dictionary (id)
    on delete set null,

  drop constraint expansion_rule_unique_name_per_parcel_and_model,
  drop constraint expansion_rule_parcel_id_fkey;

drop view sequencing.expansion_set_rule_view;

create or replace view sequencing.expansion_set_rule_view as
select str.set_id,
       rule.id,
       rule.activity_type,
       rule.expansion_logic,
       rule.authoring_command_dict_id,
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
  drop column parcel_id;


--------------------------------------
---- Modify the Expansion Set Table
--------------------------------------
alter table sequencing.expansion_set
  add column command_dict_id integer;

comment on column sequencing.expansion_set.command_dict_id is e''
  'The ID of a command dictionary';

--- Data Migration
update sequencing.expansion_set es
set command_dict_id = p.command_dictionary_id
from sequencing.parcel p
where es.parcel_id = p.id;

---

alter table sequencing.expansion_set
  alter column command_dict_id set not null;

alter table sequencing.expansion_set
  add constraint expansion_set_unique_name_per_dict_and_model
    unique (mission_model_id, command_dict_id, name),

  add foreign key (command_dict_id)
    references sequencing.command_dictionary (id)
    on delete cascade,

  drop constraint expansion_set_unique_name_per_parcel_and_model,
  drop constraint expansion_set_parcel_id_fkey;

drop view sequencing.rule_expansion_set_view;

create or replace  view sequencing.rule_expansion_set_view as
select str.rule_id,
       set.id,
       set.name,
       set.owner,
       set.description,
       set.command_dict_id,
       set.mission_model_id,
       set.created_at,
       set.updated_at,
       set.updated_by
from sequencing.expansion_set_to_rule str left join sequencing.expansion_set set
                                                    on str.set_id = set.id;


alter table sequencing.expansion_set
  drop column parcel_id;

--------------------------------------
---- Modify the User Sequence Table
--------------------------------------
alter table sequencing.user_sequence
  add column authoring_command_dict_id integer,

  add foreign key (authoring_command_dict_id)
    references sequencing.command_dictionary
    on delete cascade;


comment on column sequencing.user_sequence.authoring_command_dict_id is e''
  'Command dictionary the user sequence was created with.';

--- Data Migration
update sequencing.user_sequence us
set authoring_command_dict_id = p.command_dictionary_id
from sequencing.parcel p
where us.parcel_id = p.id;

alter table sequencing.user_sequence
  alter column authoring_command_dict_id set not null;
---

alter table sequencing.user_sequence
  drop constraint user_sequence_parcel_id_fkey,
  drop column parcel_id,
  drop column seq_json;

-------------------------------
------ Drop Parcel to Parameter Table
-------------------------------
drop table sequencing.parcel_to_parameter_dictionary;

-------------------------------
------ Drop Parameter Dictionary Table
-------------------------------
drop table sequencing.parameter_dictionary;

--------------------------------------
---- Drop the Parcel Table
--------------------------------------
drop table sequencing.parcel;

--------------------------------------
---- Drop the Seq Adaptation Table
--------------------------------------
drop table sequencing.sequence_adaptation;

--------------------------------------
---- Drop the Channel Dictionary Table
--------------------------------------
drop table sequencing.channel_dictionary;

call migrations.mark_migration_rolled_back('3');





