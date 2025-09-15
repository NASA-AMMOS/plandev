create table deliveries.target (
  name text not null,

  constraint delivery_target_primary_key
    primary key (name)
);

comment on table deliveries.target is e''
  'A target system to for deliveries to be sent to.';
comment on column deliveries.target.name is e''
  'The name of the target.';
