create table deliveries.file_to_delivery (
    filename text not null,
    delivery_id int not null,

    constraint file_to_delivery_primary_key
      primary key (filename, delivery_id),
    constraint delivery_id_fkey
      foreign key (delivery_id)
      references deliveries.delivery (id)
      on delete cascade
);

comment on table deliveries.file_to_delivery is e''
  'Join table for files and deliveries.';
comment on column deliveries.file_to_delivery.filename is e''
  'Name of the joining file.';
comment on column deliveries.file_to_delivery.delivery_id is e''
  'ID of the joining delivery.';