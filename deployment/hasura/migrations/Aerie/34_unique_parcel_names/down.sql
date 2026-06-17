alter table sequencing.parcel drop constraint parcel_name_key;
call migrations.mark_migration_rolled_back(34);
