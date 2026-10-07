package gov.nasa.ammos.plandev.workspace.server;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The revision catalog: PlanDev's authoritative record of which SeqDev file revisions exist. Rows are immutable;
 * there is deliberately no update or delete. See {@link WorkspaceRevisionService} for how rows relate to Git.
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

  @FunctionalInterface
  interface BeforeCommit {
    void accept(Revision revision) throws Exception;
  }

  /**
   * Insert the file's next revision in a transaction, run {@code beforeCommit} with the inserted row, then commit.
   * If {@code beforeCommit} throws, the insert is rolled back and the exception propagates.
   */
  Revision create(
      int workspaceId,
      UUID fileId,
      String pathAtRevision,
      String commitSha,
      String createdBy,
      BeforeCommit beforeCommit) throws Exception;

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
