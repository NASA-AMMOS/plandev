-- Services
create schema actions;
comment on schema actions is 'Actions Service Schema';
create schema merlin;
comment on schema merlin is 'Merlin Service Schema';
create schema scheduler;
comment on schema scheduler is 'Scheduler Service Schema';
create schema sequencing;
comment on schema sequencing is 'Sequencing Service Schema';
create schema ui;
comment on schema ui is 'UI Service Schema';

-- Cross Service
create schema migrations;
comment on schema migrations is 'DB Migrations Schema';
create schema hasura;
comment on schema hasura is 'Hasura Helper Function Schema';
create schema permissions;
comment on schema permissions is 'Aerie User and User Roles Schema';
create schema tags;
comment on schema tags is 'Tags Metadata Schema';
create schema util_functions;
comment on schema util_functions is 'Cross-service Helper Function Schema';
