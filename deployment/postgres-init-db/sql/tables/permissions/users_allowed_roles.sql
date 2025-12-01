create table permissions.users_allowed_roles(
  username text references permissions.users
    on update cascade
    on delete cascade,
  allowed_role text not null references permissions.user_roles
    on update cascade
    on delete cascade,

  primary key (username, allowed_role),

  constraint system_roles_have_no_allowed_roles
    check (username != 'Mission Model' and username != 'PlanDev Legacy' )
);

comment on table permissions.users_allowed_roles is e''
'An association between a user and all of the roles they are allowed to use for Hasura requests';
