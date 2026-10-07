create table sequencing.workspace_file_revision (
  id uuid not null,
  workspace_id integer not null,
  file_id uuid not null,
  ordinal bigint not null,
  display_name text not null,
  path_at_revision text not null,
  git_commit_sha text not null,
  created_by text,
  created_at timestamptz not null default now(),

  constraint workspace_file_revision_pkey
    primary key (id),
  constraint workspace_file_revision_ordinal_key
    unique (workspace_id, file_id, ordinal),
  constraint workspace_file_revision_ordinal_positive
    check (ordinal > 0),
  constraint workspace_file_revision_workspace_id_fkey
    foreign key (workspace_id) references sequencing.workspace
    on update cascade
    on delete cascade
);

comment on table sequencing.workspace_file_revision is e''
  'An explicit, immutable revision of one file in a workspace: a bookmark of the file''s state at a commit of the '
  'workspace''s Git history, mirrored there by the annotated tag plandev/revisions/<id>. Rows are never updated, and '
  'outlive the file they belong to; they are removed only with their workspace.';
comment on column sequencing.workspace_file_revision.id is e''
  'The unique id of the revision.';
comment on column sequencing.workspace_file_revision.workspace_id is e''
  'The workspace the file belongs to.';
comment on column sequencing.workspace_file_revision.file_id is e''
  'The stable identity of the file (the fileId in its metadata), which survives renames within the workspace.';
comment on column sequencing.workspace_file_revision.ordinal is e''
  'The revision''s position in its file''s history, starting at 1.';
comment on column sequencing.workspace_file_revision.display_name is e''
  'The revision''s name as shown to users (a, b, ..., z, aa, ...), assigned once and never recomputed.';
comment on column sequencing.workspace_file_revision.path_at_revision is e''
  'The file''s workspace-relative path when the revision was made: where the file is found in git_commit_sha.';
comment on column sequencing.workspace_file_revision.git_commit_sha is e''
  'The workspace history commit holding the revision''s content and metadata.';
comment on column sequencing.workspace_file_revision.created_by is e''
  'The user who made the revision. Not a foreign key: revisions are immutable history.';
comment on column sequencing.workspace_file_revision.created_at is e''
  'When the revision was made.';

call migrations.mark_migration_applied(38);
