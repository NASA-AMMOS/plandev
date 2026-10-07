package gov.nasa.ammos.plandev.workspace.server;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The revision catalog: a queryable projection of the revision tags in each workspace's Git repository
 * ({@link GitFileRevisions}), which are the authority on which revisions exist. Every row is a copy of a tag, so a
 * workspace's rows can be thrown away and rebuilt ({@link #replaceWorkspaceRevisions}). Rows are never updated.
 */
public interface WorkspaceRevisionStore {
  /**
   * One revision of one file.
   * @param ordinal the revision's position in its file's history, from 1; the stable ordering value
   * @param name the display name, assigned once from the ordinal ({@link #revisionName}) and stored, never recomputed
   * @param pathAtRevision the file's workspace-relative path when the revision was made, i.e. where to find it in
   *     {@code commitSha}'s tree (the file may have been renamed since)
   */
  record Revision(
      UUID id,
      int workspaceId,
      UUID fileId,
      long ordinal,
      String name,
      String pathAtRevision,
      String commitSha,
      String createdBy,
      Instant createdAt) {}

  /** Record a revision whose tag already exists, exactly as given. */
  void insert(Revision revision) throws Exception;

  /** In one transaction, replace all of a workspace's rows with {@code revisions}, exactly as given. */
  void replaceWorkspaceRevisions(int workspaceId, List<Revision> revisions) throws Exception;

  /** A file's revisions, oldest first. */
  List<Revision> list(int workspaceId, UUID fileId) throws Exception;

  Optional<Revision> get(int workspaceId, UUID revisionId) throws Exception;

  /**
   * The default display name for a file's {@code ordinal}-th revision: a, b, ..., z, aa, ab, ..., az, ba, ...
   * (bijective base 26). The one place revision names are made.
   */
  static String revisionName(final long ordinal) {
    if (ordinal < 1) throw new IllegalArgumentException("Revision ordinals start at 1, got " + ordinal);
    final var name = new StringBuilder();
    for (var n = ordinal; n > 0; n = (n - 1) / 26) name.append((char) ('a' + (n - 1) % 26));
    return name.reverse().toString();
  }
}
