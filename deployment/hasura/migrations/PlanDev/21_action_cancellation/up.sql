alter table actions.action_run
add column canceled boolean not null default false;

comment on column actions.action_run.canceled is e''
  'Whether the user has requested that this action be cancelled.';

create function actions.notify_action_run_cancel_requested()
  returns trigger
  security definer
  language plpgsql as $$
begin
  perform pg_notify('action_run_cancel_requested', json_build_object(
      'action_run_id', NEW.id
  )::text);
  return null;
end$$;

create trigger notify_action_run_cancel_requested
  after update on actions.action_run
  for each row
  when (
    (OLD.status != 'success' or OLD.status != 'failed')
      and NEW.canceled
      and OLD.canceled is distinct from NEW.canceled
    )
execute function actions.notify_action_run_cancel_requested();

call migrations.mark_migration_applied('21');
