-- Simulation Resources
create table hasura.resource_at_start_offset_return_value(
  dataset_id integer not null,
  id integer not null,
  name text not null,
  type jsonb,
  start_offset interval not null,
  dynamics jsonb,
  is_gap bool not null
);

create function hasura.get_resources_at_start_offset(_dataset_id int, _start_offset interval)
returns setof hasura.resource_at_start_offset_return_value
strict
stable
security invoker
language plpgsql as $$
begin
  return query
    select distinct on (p.name)
      p.dataset_id, p.id, p.name, p.type, ps.start_offset, ps.dynamics, ps.is_gap
    from merlin.profile p, merlin.profile_segment ps
	  where ps.profile_id = p.id
	    and p.dataset_id = _dataset_id
	    and ps.dataset_id = _dataset_id
	    and ps.start_offset <= _start_offset
	  order by p.name, ps.start_offset desc;
end
$$;

create function hasura.restore_activity_changelog(
  _plan_id integer,
  _activity_directive_id integer,
  _revision integer,
  hasura_session json
)
  returns setof merlin.activity_directive
  volatile
  language plpgsql as $$
declare
  _function_permission permissions.permission;
begin
  _function_permission :=
      permissions.get_function_permissions('restore_activity_changelog', hasura_session);
  if not _function_permission = 'NO_CHECK' then
    call permissions.check_general_permissions(
      'restore_activity_changelog',
      _function_permission, _plan_id,
      (hasura_session ->> 'x-hasura-user-id')
    );
  end if;

  if not exists(select id from merlin.plan where id = _plan_id) then
    raise exception 'Plan % does not exist', _plan_id;
  end if;

  if not exists(select id from merlin.activity_directive where (id, plan_id) = (_activity_directive_id, _plan_id)) then
    raise exception 'Activity Directive % does not exist in Plan %', _activity_directive_id, _plan_id;
  end if;

  if not exists(select revision
                from merlin.activity_directive_changelog
                where (plan_id, activity_directive_id, revision) =
                      (_plan_id, _activity_directive_id, _revision))
  then
    raise exception 'Changelog Revision % does not exist for Plan % and Activity Directive %', _revision, _plan_id, _activity_directive_id;
  end if;

  return query
  update merlin.activity_directive as ad
  set name                                  = c.name,
      source_scheduling_goal_id             = c.source_scheduling_goal_id,
      source_scheduling_goal_invocation_id  = c.source_scheduling_goal_invocation_id,
      start_offset                          = c.start_offset,
      type                                  = c.type,
      arguments                             = c.arguments,
      last_modified_arguments_at            = c.changed_arguments_at,
      metadata                              = c.metadata,
      anchor_id                             = c.anchor_id,
      anchored_to_start                     = c.anchored_to_start,
      last_modified_at                      = c.changed_at,
      last_modified_by                      = c.changed_by
  from merlin.activity_directive_changelog as c
  where ad.id                    = _activity_directive_id
    and c.activity_directive_id  = _activity_directive_id
    and ad.plan_id               = _plan_id
    and c.plan_id                = _plan_id
    and c.revision               = _revision
  returning ad.*;
end
$$;
