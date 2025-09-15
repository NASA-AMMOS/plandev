drop table deliveries.action_to_target;
drop table deliveries.action_to_delivery;
drop table actions.action_configuration;

drop trigger set_timestamp on deliveries.delivery;

drop table deliveries.file_to_delivery;
drop table deliveries.delivery;
drop table deliveries.target;

drop schema deliveries;

call migrations.mark_migration_rolled_back('28');
