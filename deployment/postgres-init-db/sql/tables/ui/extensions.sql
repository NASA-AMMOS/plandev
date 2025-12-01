create table ui.extensions (
  id integer generated always as identity,
  description text,
  label text not null,
  owner text references permissions.users (username)
    on update cascade
    on delete set null,
  url text not null,
  updated_at timestamptz not null default now(),

  constraint extensions_primary_key primary key (id)
);

comment on table ui.extensions is e''
  'External extension APIs the user can call from within PlanDev UI.';
comment on column ui.extensions.description is e''
  'An optional description of the external extension.';
comment on column ui.extensions.label is e''
  'The name of the extension that is displayed in the UI.';
comment on column ui.extensions.owner is e''
  'The user who owns the extension.';
comment on column ui.extensions.url is e''
  'The URL of the API to be called.';
comment on column ui.extensions.updated_at is e''
  'The time the extension was last updated.';

create trigger extensions_set_timestamp
  before update on ui.extensions
  for each row
execute function util_functions.set_updated_at();
