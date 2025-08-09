create table sequencing.workspace_collaborators(
  workspace_id int not null,
  collaborator text not null,

  constraint workspace_collaborators_pkey
    primary key (workspace_id, collaborator),
  constraint workspace_collaborators_plan_id_fkey
    foreign key (workspace_id) references sequencing.workspace
    on update cascade
    on delete cascade,
  constraint workspace_collaborator_collaborator_fkey
    foreign key (collaborator) references permissions.users
    on update cascade
    on delete cascade
);

comment on table sequencing.workspace_collaborators is e''
  'A collection of users who collaborate on the workspace alongside the workspace''s owner.';
comment on column sequencing.workspace_collaborators.workspace_id is e''
  'The plan the user is a collaborator on.';
comment on column sequencing.workspace_collaborators.collaborator is e''
  'The username of the collaborator';
