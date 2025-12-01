create table tags.tags(
  id integer generated always as identity
    primary key,
  name text not null unique,
  color text null,
  owner text,
  created_at timestamptz not null default now(),

  constraint color_is_hex_format
    check (color is null or color ~* '^#[a-f0-9]{6}$' ),
  constraint tags_owner_exists
    foreign key (owner) references permissions.users
    on update cascade
    on delete set null
);

comment on table tags.tags is e''
  'All tags usable within an PlanDev deployment.';
comment on column tags.tags.id is e''
  'The index of the tag.';
comment on column tags.tags.name is e''
  'The name of the tag. Unique within a deployment.';
comment on column tags.tags.color is e''
  'The color the tag should display as when using a GUI.';
comment on column tags.tags.owner is e''
  'The user responsible for this tag. '
  '''Mission Model'' is used to represent tags originating from an uploaded mission model'
  '''PlanDev Legacy'' is used to represent tags originating from a version of PlanDev prior to this table''s creation.';
comment on column tags.tags.created_at is e''
  'The date this tag was created.';
