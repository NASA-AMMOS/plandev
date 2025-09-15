-- create type deliveries.delivery_status as enum('Un-delivered', 'Pending', 'Delivered');

create table deliveries.delivery (
  id integer generated always as identity,
  name text,
  status text not null,
  target text not null,
  updated_at timestamptz not null default now(),
  updated_by text,

  constraint delivery_primary_key
    primary key (id),
  constraint target_fkey
    foreign key (target) references deliveries.target
    on update cascade
    on delete cascade,
  foreign key (updated_by)
    references permissions.users
    on update cascade
    on delete set null
);

create trigger set_timestamp
  before update on deliveries.delivery
  for each row
  execute function util_functions.set_updated_at();

comment on table deliveries.delivery is e''
  'A delivery.';
comment on column deliveries.delivery.id is e''
  'The ID for the delivery.';
comment on column deliveries.delivery.name is e''
  'An optional name to describe the delivery.';
comment on column deliveries.delivery.status is e''
  'The status of the delivery.';
comment on column deliveries.delivery.target is e''
  'The target to send the delivery to.';
comment on column deliveries.delivery.updated_at is e''
  'The last time the delivery was updated.';
comment on column deliveries.delivery.updated_by is e''
  'The user who last updated the delivery.';
