create procedure merlin.plan_readonly_exception(plan_id integer)
language plpgsql as $$
  begin
    if(select is_read_only from merlin.plan p where p.id = plan_id limit 1) then
      raise exception 'Plan % is marked as Read Only and cannot be edited.', plan_id;
    end if;
  end
$$;

comment on procedure merlin.plan_readonly_exception(plan_id integer) is e''
  'Checks whether the specified plan is marked as "read only", throwing an exception if it is.';
