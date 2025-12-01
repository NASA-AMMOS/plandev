create table ui.view (
  created_at timestamptz not null default now(),
  definition jsonb not null,
  id integer generated always as identity,
  name text not null,
  owner text references permissions.users (username)
    on update cascade
    on delete set null,
  updated_at timestamptz not null default now(),

  constraint view_primary_key primary key (id)
);

comment on table ui.view is e''
  'View configuration for PlanDev UI.';
comment on column ui.view.created_at is e''
  'Time the view was created.';
comment on column ui.view.definition is e''
  'JSON blob of the view definition that implements the view JSON schema.';
comment on column ui.view.id is e''
  'Integer primary key of the view.';
comment on column ui.view.name is e''
  'Human-readable name of the view.';
comment on column ui.view.owner is e''
  'The user who owns the view.';
comment on column ui.view.updated_at is e''
  'Time the view was last updated.';

create trigger set_timestamp
before update on ui.view
for each row
execute function util_functions.set_updated_at();
