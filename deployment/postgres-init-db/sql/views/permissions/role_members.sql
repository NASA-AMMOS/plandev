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
