create view permissions.role_members as
(
  select username, default_role as role
  from permissions.users
  union
  select username, allowed_role as role
  from permissions.users_allowed_roles
);

comment on view permissions.role_members is e''
'The roles each user belongs to: their default role plus all their allowed roles.';

create table sequencing.workspace_role_grants(
  workspace_id int not null,
  role text not null,

  constraint workspace_role_grants_pkey
    primary key (workspace_id, role),
  constraint workspace_role_grants_workspace_id_fkey
    foreign key (workspace_id) references sequencing.workspace
    on update cascade
    on delete cascade,
  constraint workspace_role_grants_role_fkey
    foreign key (role) references permissions.user_roles
    on update cascade
    on delete cascade
);

comment on table sequencing.workspace_role_grants is e''
  'Roles whose members can edit the workspace''s files, alongside its owner and collaborators. '
  'Membership is evaluated live (see permissions.role_members).';
comment on column sequencing.workspace_role_grants.workspace_id is e''
  'The workspace the role has been granted access to.';
comment on column sequencing.workspace_role_grants.role is e''
  'The role being granted access.';

call migrations.mark_migration_applied(39);
