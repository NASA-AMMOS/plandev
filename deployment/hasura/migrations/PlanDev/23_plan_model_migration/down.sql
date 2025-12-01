-- #### PERMISSIONS ####

-- Remove user permissions (see default_user_roles)
update permissions.user_role_permission
set function_permissions = function_permissions  - 'migrate_plan_to_model';

-- Need to remove the key 'migrate_plan_to_model' from function_permission_key enum, only way is to drop and recreate it.
----- 1. drop any functions/procedures/objects that depend on permissions.function_permission_key to avoid errors
drop function permissions.raise_if_plan_merge_permission(_function permissions.function_permission_key, _permission permissions.permission);
drop procedure permissions.check_merge_permissions(_function permissions.function_permission_key, _merge_request_id integer, hasura_session json);
drop procedure permissions.check_merge_permissions(_function permissions.function_permission_key, _permission permissions.permission, _plan_id_receiving integer, _plan_id_supplying integer, _user text);
drop procedure permissions.check_general_permissions(_function permissions.function_permission_key, _permission permissions.permission, _plan_id integer, _user text);
drop function permissions.get_function_permissions(_function permissions.function_permission_key, hasura_session json);

------ 2. drop permissions.function_permission_key and recreate without 'migrate_plan_to_model'
drop type permissions.function_permission_key;
create type permissions.function_permission_key
as enum ('apply_preset', 'begin_merge', 'branch_plan', 'cancel_merge', 'commit_merge', 'create_merge_rq',
  'create_snapshot', 'delete_activity_reanchor', 'delete_activity_reanchor_bulk', 'delete_activity_reanchor_plan',
  'delete_activity_reanchor_plan_bulk', 'delete_activity_subtree', 'delete_activity_subtree_bulk', 'deny_merge',
  'get_conflicting_activities', 'get_non_conflicting_activities', 'get_plan_history',  'restore_activity_changelog',
  'restore_snapshot', 'set_resolution', 'set_resolution_bulk', 'withdraw_merge_rq');

------ 3. finally recreate the dependent functions/procedures of function_permission_key
create function permissions.get_function_permissions(_function permissions.function_permission_key, hasura_session json)
returns permissions.permission
stable
language plpgsql as $$
declare
  _role text;
  _function_permission permissions.permission;
begin
  _role := permissions.get_role(hasura_session);
  -- The aerie_admin role is always treated as having NO_CHECK permissions on all functions.
  if _role = 'aerie_admin' then return 'NO_CHECK'; end if;

  select (function_permissions ->> _function::text)::permissions.permission
  from permissions.user_role_permission urp
  where urp.role = _role
  into _function_permission;

  -- The absence of the function key means that the role does not have permission to perform the function.
  if _function_permission is null then
    raise insufficient_privilege
      using message = 'User with role '''|| _role ||''' is not permitted to run '''|| _function ||'''';
  end if;

  return _function_permission::permissions.permission;
end
$$;

create procedure permissions.check_general_permissions(
  _function permissions.function_permission_key,
  _permission permissions.permission,
  _plan_id integer,
  _user text)
language plpgsql as $$
declare
  _mission_model_id integer;
  _plan_name text;
begin
  select name from merlin.plan where id = _plan_id into _plan_name;

  -- MISSION_MODEL_OWNER: The user must own the relevant Mission Model
  if _permission = 'MISSION_MODEL_OWNER' then
    select id from merlin.mission_model mm
    where mm.id = (select model_id from merlin.plan p where p.id = _plan_id)
    into _mission_model_id;

    if not exists(select * from merlin.mission_model mm where mm.id = _mission_model_id and mm.owner =_user) then
        raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not MISSION_MODEL_OWNER on Model ' || _mission_model_id ||'.';
    end if;

  -- OWNER: The user must be the owner of all relevant objects directly used by the KEY
  -- In most cases, OWNER is equivalent to PLAN_OWNER. Use a custom solution when that is not true.
  elseif _permission = 'OWNER' then
		if not exists(select * from merlin.plan p where p.id = _plan_id and p.owner = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not OWNER on Plan ' || _plan_id ||' ('|| _plan_name ||').';
    end if;

  -- PLAN_OWNER: The user must be the Owner of the relevant Plan
  elseif _permission = 'PLAN_OWNER' then
    if not exists(select * from merlin.plan p where p.id = _plan_id and p.owner = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_OWNER on Plan '|| _plan_id ||' ('|| _plan_name ||').';
    end if;

  -- PLAN_COLLABORATOR:	The user must be a Collaborator of the relevant Plan. The Plan Owner is NOT considered a Collaborator of the Plan
  elseif _permission = 'PLAN_COLLABORATOR' then
    if not exists(select * from merlin.plan_collaborators pc where pc.plan_id = _plan_id and pc.collaborator = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_COLLABORATOR on Plan '|| _plan_id ||' ('|| _plan_name ||').';
    end if;

  -- PLAN_OWNER_COLLABORATOR:	The user must be either the Owner or a Collaborator of the relevant Plan
  elseif _permission = 'PLAN_OWNER_COLLABORATOR' then
    if not exists(select * from merlin.plan p where p.id = _plan_id and p.owner = _user) then
      if not exists(select * from merlin.plan_collaborators pc where pc.plan_id = _plan_id and pc.collaborator = _user) then
        raise insufficient_privilege
          using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_OWNER_COLLABORATOR on Plan '|| _plan_id ||' ('|| _plan_name ||').';
      end if;
    end if;
  end if;
end
$$;

create procedure permissions.check_merge_permissions(_function permissions.function_permission_key, _merge_request_id integer, hasura_session json)
language plpgsql as $$
declare
  _plan_id_receiving_changes integer;
  _plan_id_supplying_changes integer;
  _function_permission permissions.permission;
  _user text;
begin
  select plan_id_receiving_changes
  from merlin.merge_request mr
  where mr.id = _merge_request_id
  into _plan_id_receiving_changes;

  select plan_id
  from merlin.plan_snapshot ps, merlin.merge_request mr
  where mr.id = _merge_request_id and ps.snapshot_id = mr.snapshot_id_supplying_changes
  into _plan_id_supplying_changes;

  _user := (hasura_session ->> 'x-hasura-user-id');
  _function_permission := permissions.get_function_permissions('get_non_conflicting_activities', hasura_session);
  call permissions.check_merge_permissions(_function, _function_permission, _plan_id_receiving_changes,
    _plan_id_supplying_changes, _user);
end
$$;

create procedure permissions.check_merge_permissions(
  _function permissions.function_permission_key,
  _permission permissions.permission,
  _plan_id_receiving integer,
  _plan_id_supplying integer,
  _user text)
language plpgsql as $$
declare
  _supplying_plan_name text;
  _receiving_plan_name text;
begin
  select name from merlin.plan where id = _plan_id_supplying into _supplying_plan_name;
  select name from merlin.plan where id = _plan_id_receiving into _receiving_plan_name;

  -- MISSION_MODEL_OWNER: The user must own the relevant Mission Model
  if _permission = 'MISSION_MODEL_OWNER' then
    call permissions.check_general_permissions(_function, _permission, _plan_id_receiving, _user);

  -- OWNER: The user must be the Owner of both Plans
  elseif _permission = 'OWNER' then
    if not (exists(select * from merlin.plan p where p.id = _plan_id_receiving and p.owner = _user)) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''
                          || _user ||''' is not OWNER on Plan '|| _plan_id_receiving
                          ||' ('|| _receiving_plan_name ||').';
    elseif not (exists(select * from merlin.plan p2 where p2.id = _plan_id_supplying and p2.owner = _user)) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''
                          || _user ||''' is not OWNER on Plan '|| _plan_id_supplying
                          ||' ('|| _supplying_plan_name ||').';
    end if;

  -- PLAN_OWNER: The user must be the Owner of either Plan
  elseif _permission = 'PLAN_OWNER' then
    if not exists(select *
                  from merlin.plan p
                  where (p.id = _plan_id_receiving or p.id = _plan_id_supplying)
                    and p.owner = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''
                          || _user ||''' is not PLAN_OWNER on either Plan '|| _plan_id_receiving
                          ||' ('|| _receiving_plan_name ||') or Plan '|| _plan_id_supplying ||' ('|| _supplying_plan_name ||').';
    end if;

  -- PLAN_COLLABORATOR:	The user must be a Collaborator of either Plan. The Plan Owner is NOT considered a Collaborator of the Plan
  elseif _permission = 'PLAN_COLLABORATOR' then
    if not exists(select *
                  from merlin.plan_collaborators pc
                  where (pc.plan_id = _plan_id_receiving or pc.plan_id = _plan_id_supplying)
                    and pc.collaborator = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''
                          || _user ||''' is not PLAN_COLLABORATOR on either Plan '|| _plan_id_receiving
                          ||' ('|| _receiving_plan_name ||') or Plan '|| _plan_id_supplying ||' ('|| _supplying_plan_name ||').';
    end if;

  -- PLAN_OWNER_COLLABORATOR:	The user must be either the Owner or a Collaborator of either Plan
  elseif _permission = 'PLAN_OWNER_COLLABORATOR' then
    if not exists(select *
                  from merlin.plan p
                  where (p.id = _plan_id_receiving or p.id = _plan_id_supplying)
                    and p.owner = _user) then
      if not exists(select *
                    from merlin.plan_collaborators pc
                    where (pc.plan_id = _plan_id_receiving or pc.plan_id = _plan_id_supplying)
                      and pc.collaborator = _user) then
        raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''
                          || _user ||''' is not PLAN_OWNER_COLLABORATOR on either Plan '|| _plan_id_receiving
                          ||' ('|| _receiving_plan_name ||') or Plan '|| _plan_id_supplying ||' ('|| _supplying_plan_name ||').';

      end if;
    end if;

  -- PLAN_OWNER_SOURCE:	The user must be the Owner of the Supplying Plan
  elseif _permission = 'PLAN_OWNER_SOURCE' then
    if not exists(select *
                  from merlin.plan p
                  where p.id = _plan_id_supplying and p.owner = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_OWNER on Source Plan '
                          || _plan_id_supplying ||' ('|| _supplying_plan_name ||').';
    end if;

  -- PLAN_COLLABORATOR_SOURCE: The user must be a Collaborator of the Supplying Plan.
  elseif _permission = 'PLAN_COLLABORATOR_SOURCE' then
    if not exists(select *
                  from merlin.plan_collaborators pc
                  where pc.plan_id = _plan_id_supplying and pc.collaborator = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_COLLABORATOR on Source Plan '
                          || _plan_id_supplying ||' ('|| _supplying_plan_name ||').';
    end if;

  -- PLAN_OWNER_COLLABORATOR_SOURCE:	The user must be either the Owner or a Collaborator of the Supplying Plan.
  elseif _permission = 'PLAN_OWNER_COLLABORATOR_SOURCE' then
    if not exists(select *
                  from merlin.plan p
                  where p.id = _plan_id_supplying and p.owner = _user) then
      if not exists(select *
                    from merlin.plan_collaborators pc
                    where pc.plan_id = _plan_id_supplying and pc.collaborator = _user) then
        raise insufficient_privilege
          using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_OWNER_COLLABORATOR on Source Plan '
                          || _plan_id_supplying ||' ('|| _supplying_plan_name ||').';
      end if;
    end if;

  -- PLAN_OWNER_TARGET: The user must be the Owner of the Receiving Plan.
  elseif _permission = 'PLAN_OWNER_TARGET' then
    if not exists(select *
                  from merlin.plan p
                  where p.id = _plan_id_receiving and p.owner = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_OWNER on Target Plan '
                          || _plan_id_receiving ||' ('|| _receiving_plan_name ||').';
    end if;

  -- PLAN_COLLABORATOR_TARGET: The user must be a Collaborator of the Receiving Plan.
  elseif _permission = 'PLAN_COLLABORATOR_TARGET' then
    if not exists(select *
                  from merlin.plan_collaborators pc
                  where pc.plan_id = _plan_id_receiving and pc.collaborator = _user) then
      raise insufficient_privilege
        using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_COLLABORATOR on Target Plan '
                          || _plan_id_receiving ||' ('|| _receiving_plan_name ||').';
    end if;

  -- PLAN_OWNER_COLLABORATOR_TARGET: The user must be either the Owner or a Collaborator of the Receiving Plan.
  elseif _permission = 'PLAN_OWNER_COLLABORATOR_TARGET' then
    if not exists(select *
                  from merlin.plan p
                  where p.id = _plan_id_receiving and p.owner = _user) then
      if not exists(select *
                    from merlin.plan_collaborators pc
                    where pc.plan_id = _plan_id_receiving and pc.collaborator = _user) then
        raise insufficient_privilege
          using message = 'Cannot run '''|| _function ||''': '''|| _user ||''' is not PLAN_OWNER_COLLABORATOR on Target Plan '
                          || _plan_id_receiving ||' ('|| _receiving_plan_name ||').';
      end if;
    end if;
  end if;
end
$$;

create function permissions.raise_if_plan_merge_permission(_function permissions.function_permission_key, _permission permissions.permission)
  returns void
  immutable
  language plpgsql as $$
begin
  if _permission::text = any(array['PLAN_OWNER_SOURCE', 'PLAN_COLLABORATOR_SOURCE', 'PLAN_OWNER_COLLABORATOR_SOURCE',
    'PLAN_OWNER_TARGET', 'PLAN_COLLABORATOR_TARGET', 'PLAN_OWNER_COLLABORATOR_TARGET'])
  then
    raise 'Invalid Permission: The Permission ''%'' may not be applied to function ''%''', _permission, _function;
  end if;
end
$$;

-- done modifying permissions.function_permission_key enum

-- #### END PERMISSIONS ####


-- Remove plan migration check functions
drop function hasura.check_model_compatibility_for_plan(_plan_id integer, _new_model_id integer);
drop table hasura.check_model_compatibility_for_plan_return_value;

drop function hasura.check_model_compatibility(_old_model_id integer, _new_model_id integer);
drop table hasura.check_model_compatibility_return_value;

-- Remove plan migration function
drop function hasura.migrate_plan_to_model(_plan_id integer, _new_model_id integer, hasura_session json);
drop table hasura.migrate_plan_to_model_return_value;

-- Drop model_id column from simulation_dataset
alter table merlin.simulation_dataset
  drop column model_id;


-- Restore state of set_revisions_and_initialize_dataset_on_insert

create or replace function merlin.set_revisions_and_initialize_dataset_on_insert()
returns trigger
security definer
language plpgsql as $$
declare
  simulation_ref merlin.simulation;
  plan_ref merlin.plan;
  model_ref merlin.mission_model;
  template_ref merlin.simulation_template;
  dataset_ref merlin.dataset;
begin
  -- Set the revisions
  select into simulation_ref * from merlin.simulation where id = new.simulation_id;
  select into plan_ref * from merlin.plan where id = simulation_ref.plan_id;
  select into template_ref * from merlin.simulation_template where id = simulation_ref.simulation_template_id;
  select into model_ref * from merlin.mission_model where id = plan_ref.model_id;
  new.model_revision = model_ref.revision;
  new.plan_revision = plan_ref.revision;
  new.simulation_template_revision = template_ref.revision;
  new.simulation_revision = simulation_ref.revision;

  -- Create the dataset
  insert into merlin.dataset
  default values
  returning * into dataset_ref;
  new.dataset_id = dataset_ref.id;
  new.dataset_revision = dataset_ref.revision;
return new;
end$$;

-- End of set_revisions_and_initialize_dataset_on_insert changes


-- Restore merge request functions to state before this migration
create or replace function merlin.create_merge_request(plan_id_supplying integer, plan_id_receiving integer, request_username text)
  returns integer
  language plpgsql as $$
declare
  merge_base_snapshot_id integer;
  validate_planIds integer;
  supplying_snapshot_id integer;
  merge_request_id integer;
begin
  if plan_id_receiving = plan_id_supplying then
    raise exception 'Cannot create a merge request between a plan and itself.';
  end if;
  select id from merlin.plan where plan.id = plan_id_receiving into validate_planIds;
  if validate_planIds is null then
    raise exception 'Plan receiving changes (Plan %) does not exist.', plan_id_receiving;
  end if;
  select id from merlin.plan where plan.id = plan_id_supplying into validate_planIds;
  if validate_planIds is null then
    raise exception 'Plan supplying changes (Plan %) does not exist.', plan_id_supplying;
  end if;

  select merlin.create_snapshot(plan_id_supplying) into supplying_snapshot_id;

  select merlin.get_merge_base(plan_id_receiving, supplying_snapshot_id) into merge_base_snapshot_id;
  if merge_base_snapshot_id is null then
    raise exception 'Cannot create merge request between unrelated plans.';
  end if;

  insert into merlin.merge_request(plan_id_receiving_changes, snapshot_id_supplying_changes, merge_base_snapshot_id, requester_username)
    values(plan_id_receiving, supplying_snapshot_id, merge_base_snapshot_id, request_username)
    returning id into merge_request_id;
  return merge_request_id;
end
$$;
-- End of merge request changes


-- Restore the restore from snapshot function to state before this migration
create or replace procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer)
	language plpgsql as $$
	declare
		_snapshot_name text;
		_plan_name text;
	begin
		-- Input Validation
		select name from merlin.plan where id = _plan_id into _plan_name;
		if _plan_name is null then
			raise exception 'Cannot Restore: Plan with ID % does not exist.', _plan_id;
		end if;
		if not exists(select snapshot_id from merlin.plan_snapshot where snapshot_id = _snapshot_id) then
			raise exception 'Cannot Restore: Snapshot with ID % does not exist.', _snapshot_id;
		end if;
		if not exists(select snapshot_id from merlin.plan_snapshot where _snapshot_id = snapshot_id and _plan_id = plan_id ) then
			select snapshot_name from merlin.plan_snapshot where snapshot_id = _snapshot_id into _snapshot_name;
			if _snapshot_name is not null then
        raise exception 'Cannot Restore: Snapshot ''%'' (ID %) is not a snapshot of Plan ''%'' (ID %)',
          _snapshot_name, _snapshot_id, _plan_name, _plan_id;
      else
				raise exception 'Cannot Restore: Snapshot % is not a snapshot of Plan ''%'' (ID %)',
          _snapshot_id, _plan_name, _plan_id;
      end if;
    end if;

		-- Catch Plan_Locked
		call merlin.plan_locked_exception(_plan_id);

    -- Record the Union of Activities in Plan and Snapshot
    -- and note which ones have been added since the Snapshot was taken (in_snapshot = false)
    create temp table diff(
			activity_id integer,
			in_snapshot boolean not null
		);
		insert into diff(activity_id, in_snapshot)
		select id as activity_id, true
		from merlin.plan_snapshot_activities where snapshot_id = _snapshot_id;

		insert into diff (activity_id, in_snapshot)
		select activity_id, false
		from(
				select id as activity_id
				from merlin.activity_directive
				where plan_id = _plan_id
			except
				select activity_id
				from diff) a;

		-- Remove any added activities
  delete from merlin.activity_directive ad
		using diff d
		where (ad.id, ad.plan_id) = (d.activity_id, _plan_id)
			and d.in_snapshot is false;

		-- Upsert the rest
		insert into merlin.activity_directive (
		      id, plan_id, name, source_scheduling_goal_id, created_at, created_by, last_modified_at, last_modified_by,
		      start_offset, type, arguments, last_modified_arguments_at, metadata,
		      anchor_id, anchored_to_start)
		select psa.id, _plan_id, psa.name, psa.source_scheduling_goal_id, psa.created_at, psa.created_by, psa.last_modified_at, psa.last_modified_by,
		       psa.start_offset, psa.type, psa.arguments, psa.last_modified_arguments_at, psa.metadata,
		       psa.anchor_id, psa.anchored_to_start
		from merlin.plan_snapshot_activities psa
		where psa.snapshot_id = _snapshot_id
		on conflict (id, plan_id) do update
		-- 'last_modified_at' and 'last_modified_arguments_at' are skipped during update, as triggers will overwrite them to now()
		set name = excluded.name,
		    source_scheduling_goal_id = excluded.source_scheduling_goal_id,
		    created_at = excluded.created_at,
		    created_by = excluded.created_by,
		    last_modified_by = excluded.last_modified_by,
		    start_offset = excluded.start_offset,
		    type = excluded.type,
		    arguments = excluded.arguments,
		    metadata = excluded.metadata,
		    anchor_id = excluded.anchor_id,
		    anchored_to_start = excluded.anchored_to_start;

		-- Tags
		delete from tags.activity_directive_tags adt
		using diff d
		where (adt.directive_id, adt.plan_id) = (d.activity_id, _plan_id);

		insert into tags.activity_directive_tags(directive_id, plan_id, tag_id)
		select sat.directive_id, _plan_id, sat.tag_id
		from tags.snapshot_activity_tags sat
		where sat.snapshot_id = _snapshot_id
		on conflict (directive_id, plan_id, tag_id) do nothing;

		-- Presets
		delete from merlin.preset_to_directive
		  where plan_id = _plan_id;
		insert into merlin.preset_to_directive(preset_id, activity_id, plan_id)
			select pts.preset_id, pts.activity_id, _plan_id
			from merlin.preset_to_snapshot_directive pts
			where pts.snapshot_id = _snapshot_id
			on conflict (activity_id, plan_id)
			do update	set preset_id = excluded.preset_id;

		-- Clean up
		drop table diff;
  end
$$;

comment on procedure merlin.restore_from_snapshot(_plan_id integer, _snapshot_id integer) is e''
	'Restore a plan to its state described in the given snapshot.';
-- End of restore from snapshot changes


-- Restore plan snapshot functions to state before this migration
create or replace function merlin.create_snapshot(_plan_id integer, _snapshot_name text, _description text, _user text)
  returns integer -- snapshot id inserted into the table
  language plpgsql as $$
  declare
    validate_plan_id integer;
    inserted_snapshot_id integer;
begin
  select id from merlin.plan where plan.id = _plan_id into validate_plan_id;
  if validate_plan_id is null then
    raise exception 'Plan % does not exist.', _plan_id;
  end if;

  insert into merlin.plan_snapshot(plan_id, revision, snapshot_name, description, taken_by)
    select id, revision, _snapshot_name, _description, _user
    from merlin.plan where id = _plan_id
    returning snapshot_id into inserted_snapshot_id;
  insert into merlin.plan_snapshot_activities(
      snapshot_id, id, name, source_scheduling_goal_id, created_at, created_by,
      last_modified_at, last_modified_by, start_offset, type,
      arguments, last_modified_arguments_at, metadata, anchor_id, anchored_to_start)
    select
      inserted_snapshot_id,                              -- this is the snapshot id
      id, name, source_scheduling_goal_id, created_at, created_by, -- these are the rest of the data for an activity row
      last_modified_at, last_modified_by, start_offset, type,
      arguments, last_modified_arguments_at, metadata, anchor_id, anchored_to_start
    from merlin.activity_directive where activity_directive.plan_id = _plan_id;
  insert into merlin.preset_to_snapshot_directive(preset_id, activity_id, snapshot_id)
    select ptd.preset_id, ptd.activity_id, inserted_snapshot_id
    from merlin.preset_to_directive ptd
    where ptd.plan_id = _plan_id;
  insert into tags.snapshot_activity_tags(snapshot_id, directive_id, tag_id)
    select inserted_snapshot_id, directive_id, tag_id
    from tags.activity_directive_tags adt
    where adt.plan_id = _plan_id;

  --all snapshots in plan_latest_snapshot for plan plan_id become the parent of the current snapshot
  insert into merlin.plan_snapshot_parent(snapshot_id, parent_snapshot_id)
    select inserted_snapshot_id, snapshot_id
    from merlin.plan_latest_snapshot where plan_latest_snapshot.plan_id = _plan_id;

  --remove all of those entries from plan_latest_snapshot and add this new snapshot.
  delete from merlin.plan_latest_snapshot where plan_latest_snapshot.plan_id = _plan_id;
  insert into merlin.plan_latest_snapshot(plan_id, snapshot_id) values (_plan_id, inserted_snapshot_id);

  return inserted_snapshot_id;
  end;
$$;

comment on function merlin.create_snapshot(integer) is e''
	'See comment on create_snapshot(integer, text, text, text)';

comment on function merlin.create_snapshot(integer, text, text, text) is e''
  'Create a snapshot of the specified plan. A snapshot consists of:'
  '  - The plan''s id and revision'
  '  - All the activities in the plan'
  '  - The preset status of those activities'
  '  - The tags on those activities'
	'  - When the snapshot was taken'
	'  - Optionally: who took the snapshot, a name for the snapshot, a description of the snapshot';

-- End of create_snapshot changes

-- Remove model_id from plan snapshot
alter table merlin.plan_snapshot
  drop column model_id;

call migrations.mark_migration_rolled_back('23');
