package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistory.Kind;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceHistory.WorkspaceHistoryException;
import gov.nasa.ammos.plandev.workspace.server.WorkspaceRevisionStore.Revision;
import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * The authoritative record of SeqDev file revisions: one annotated tag {@code refs/tags/plandev/revisions/<revision id>}
 * per revision, on the commit holding it, whose message is a small JSON record ({@link #annotation}). A revision exists
 * because its tag does; the {@link WorkspaceRevisionStore} is a projection of these tags that can be rebuilt from them.
 *
 * <p>Only refs under that prefix are considered, and each must be a well-formed, self-consistent revision tag. Other
 * tags and commit messages are never read as revisions.
 */
final class GitFileRevisions {
  static final String TAG_PREFIX = "plandev/revisions/";
  private static final String REF_PREFIX = Constants.R_TAGS + TAG_PREFIX;
  private static final String TYPE = "plandev-file-revision";
  private static final int VERSION = 1;

  private GitFileRevisions() {}

  /** One or more recognized revision tags are invalid. Nothing was read from any of them. */
  static final class InvalidRevisionTagsException extends WorkspaceHistoryException {
    final List<String> problems;

    InvalidRevisionTagsException(final List<String> problems) {
      super(Kind.REVISION_TAG_INVALID, "Invalid PlanDev revision tags: " + String.join("; ", problems), null);
      this.problems = List.copyOf(problems);
    }
  }

  private static final class Invalid extends Exception {
    Invalid(final String message) {
      super(message, null, false, false);
    }
  }

  /**
   * Every revision recorded in the repository, ordered by file and ordinal. All or nothing: if any recognized tag is
   * invalid, or two tags claim the same ordinal of a file, nothing is returned and every problem is reported.
   * (A revision id cannot be claimed twice: the tag name must be the id's canonical form.)
   */
  static List<Revision> readAll(final Repository repo, final int workspaceId) throws IOException {
    final var revisions = new ArrayList<Revision>();
    final var problems = new ArrayList<String>();
    try (final var walk = new RevWalk(repo)) {
      for (final var ref : repo.getRefDatabase().getRefsByPrefix(REF_PREFIX)) {
        try {
          revisions.add(parse(repo, walk, ref, workspaceId));
        } catch (Invalid e) {
          problems.add(ref.getName() + ": " + e.getMessage());
        }
      }
    }
    final var claimed = new HashMap<List<Object>, UUID>();
    for (final var r : revisions) {
      final var other = claimed.putIfAbsent(List.of(r.fileId(), r.ordinal()), r.id());
      if (other != null) {
        problems.add("revisions %s and %s both claim ordinal %d of file %s".formatted(other, r.id(), r.ordinal(), r.fileId()));
      }
    }
    if (!problems.isEmpty()) throw new InvalidRevisionTagsException(problems);
    revisions.sort(Comparator.comparing(Revision::fileId).thenComparingLong(Revision::ordinal));
    return revisions;
  }

  private static Revision parse(final Repository repo, final RevWalk walk, final Ref ref, final int workspaceId)
  throws IOException, Invalid
  {
    if (!(walk.parseAny(ref.getObjectId()) instanceof RevTag tag)) throw new Invalid("not an annotated tag");
    final JsonObject a;
    try (final var reader = Json.createReader(new StringReader(tag.getFullMessage()))) {
      a = reader.readObject();
    } catch (JsonException | IllegalStateException e) {
      throw new Invalid("its annotation is not a JSON object");
    }
    if (!TYPE.equals(string(a, "type"))) throw new Invalid("its annotation type is not " + TYPE);
    if (!(a.get("version") instanceof JsonNumber v && v.isIntegral() && v.intValue() == VERSION)) {
      throw new Invalid("unsupported annotation version " + a.get("version"));
    }
    final var id = uuid(a, "revisionId");
    if (!ref.getName().equals(REF_PREFIX + id)) throw new Invalid("its name does not match its revisionId " + id);
    final var fileId = uuid(a, "fileId");
    final long ordinal;
    try {
      ordinal = ((JsonNumber) a.get("ordinal")).longValueExact();
    } catch (ClassCastException | NullPointerException | ArithmeticException e) {
      throw new Invalid("ordinal must be an integer");
    }
    if (ordinal <= 0) throw new Invalid("ordinal must be positive");
    final var name = string(a, "name");
    final var path = string(a, "path");
    final Instant createdAt;
    try {
      createdAt = Instant.parse(string(a, "createdAt"));
    } catch (DateTimeParseException e) {
      throw new Invalid("createdAt is not an ISO-8601 instant");
    }
    final String createdBy;
    final var by = a.getOrDefault("createdBy", JsonValue.NULL);
    if (by == JsonValue.NULL) createdBy = null;
    else if (by instanceof JsonString s) createdBy = s.getString();
    else throw new Invalid("createdBy must be a string or null");

    if (tag.getObject().getType() != Constants.OBJ_COMMIT) throw new Invalid("it does not point at a commit");
    final RevCommit commit;
    try {
      commit = walk.parseCommit(tag.getObject());
    } catch (MissingObjectException e) {
      throw new Invalid("its commit " + tag.getObject().name() + " is missing");
    }
    requireFile(repo, commit, path);
    final JsonObject sidecar;
    try (final var reader = Json.createReader(new StringReader(
        new String(repo.open(requireFile(repo, commit, sidecarKey(path))).getBytes(), StandardCharsets.UTF_8)))) {
      sidecar = reader.readObject();
    } catch (JsonException | IllegalStateException e) {
      throw new Invalid("the metadata of " + path + " is not a JSON object");
    }
    if (!fileId.equals(uuid(sidecar, "fileId"))) {
      throw new Invalid("the metadata of %s does not have fileId %s".formatted(path, fileId));
    }
    return new Revision(id, workspaceId, fileId, ordinal, name, path,
                        commit.name(), createdBy, createdAt);
  }

  /** The tag's message: a deliberately small, versioned JSON record carrying every field of the revision. */
  static String annotation(final Revision revision) {
    final var json = Json.createObjectBuilder()
        .add("type", TYPE)
        .add("version", VERSION)
        .add("revisionId", revision.id().toString())
        .add("fileId", revision.fileId().toString())
        .add("ordinal", revision.ordinal())
        .add("path", revision.pathAtRevision())
        .add("name", revision.name())
        .add("createdAt", revision.createdAt().toString());
    if (revision.createdBy() == null) json.addNull("createdBy");
    else json.add("createdBy", revision.createdBy());
    return json.build().toString() + "\n";
  }

  static String sidecarKey(final String key) {
    final var slash = key.lastIndexOf('/');
    return key.substring(0, slash + 1) + RenderType.toMetadataFileName(key.substring(slash + 1));
  }

  private static ObjectId requireFile(final Repository repo, final RevCommit commit, final String path)
  throws IOException, Invalid
  {
    try (final var tw = TreeWalk.forPath(repo, path, commit.getTree())) {
      if (tw == null || !FileMode.REGULAR_FILE.equals(tw.getRawMode(0)) && !FileMode.EXECUTABLE_FILE.equals(tw.getRawMode(0))) {
        throw new Invalid(path + " is not a file in commit " + commit.name());
      }
      return tw.getObjectId(0);
    } catch (IllegalArgumentException e) { // not a valid repository path
      throw new Invalid(path + " is not a file in commit " + commit.name());
    }
  }

  private static String string(final JsonObject json, final String key) throws Invalid {
    if (json.get(key) instanceof JsonString s && !s.getString().isEmpty()) return s.getString();
    throw new Invalid(key + " must be a non-empty string");
  }

  private static UUID uuid(final JsonObject json, final String key) throws Invalid {
    try {
      return UUID.fromString(string(json, key));
    } catch (IllegalArgumentException e) {
      throw new Invalid(key + " is not a UUID");
    }
  }
}
