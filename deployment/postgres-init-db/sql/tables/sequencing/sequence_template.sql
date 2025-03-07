create table sequencing.sequence_template (
  id integer generated always as identity,
  name text not null,

  model_id integer not null,
  parcel_id integer not null,
  template_definition text not null,
  activity_type text not null,
  language text not null,
  owner text,

    constraint sequence_template_pkey primary key (id),
    constraint activity_type foreign key (activity_type, model_id)
        references merlin.activity_type (name, model_id) match simple
        on update cascade
        on delete cascade
        NOT VALID,
    constraint "model_id -> merlin.mission_model.id" foreign key (model_id)
        references merlin.mission_model (id) match simple
        on update cascade
        on delete cascade,
    constraint "parcel_id -> sequencing.parcel.id" foreign key (parcel_id)
        references sequencing.parcel (id) match simple
        on update cascade
        on delete cascade,

  constraint only_one_template_per_model_activity_type
      unique (model_id, activity_type)
);

-- Introduce a trigger that verifies that, for a given (model_id, parcel_id) tuple, the language is consistent.
--    TODO: Discuss alternative implementations. This is fine, but should language be in parcel? That works but sort of breaks when considering that
--          parcel is in use in the legacy implementation of sequencing. But what about using workspaces? For now, leaving as is, but worth discussing further.
create or replace function sequencing.check_language_sameness()
	returns trigger
	language plpgsql as $$
begin
	-- If there are no entries for this model,parcel tuple, proceed.
	if (select count(language) as existing_entries from sequencing.sequence_template where model_id=1 and parcel_id=1 limit 1) <= 0 then
		return new;
	end if;
	-- If there are entries for this model,parcel tuple, ensure the language is the same.
    if (not new.language = (select language from sequencing.sequence_template where model_id=new.model_id and parcel_id=new.parcel_id limit 1)) then
		raise foreign_key_violation
		using message='Languages must be the same for a given (model_id, parcel_id) tuple.';
	end if;
	return new;
end;
$$;

create or replace trigger ensure_language_match
before insert or update on sequencing.sequence_template
	for each row execute function sequencing.check_language_sameness();
