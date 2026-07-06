package gov.nasa.jpl.aerie.workspace.server;

import javax.json.Json;
import javax.json.JsonObject;

/**
 * One immutable revision of a single workspace file, as surfaced by the versioning API.
 *
 * <p>A revision is a Git <em>tag</em> on a save-commit (Approach 2). Its content lives in a Git blob; the
 * {@link #contentHash} is the SHA-256 content token — the <em>same</em> value the edit-protection layer
 * returns as an {@code ETag} — so "is the working copy dirty since revision N" is just
 * {@code workingCopyETag != revision.contentHash}. Identity is the file's <em>path</em> (git log --follow),
 * so there is no synthetic file id.
 *
 * @param number      1-based per-file revision number (independent per file)
 * @param name        auto-assigned name (a, b, c …) frozen at creation
 * @param path        the file's path (relative to the workspace root) as of this revision
 * @param contentHash SHA-256 content token, identical to the edit-protection ETag
 * @param author      the revision's author (a PlanDev userId for in-app revisions)
 * @param createdAt   ISO-8601 creation timestamp
 * @param message     optional free-form message ("" if none)
 * @param commitSha   the underlying Git commit id (internal detail, useful for debugging/mirror)
 */
public record WorkspaceFileRevision(
    int number,
    String name,
    String path,
    String contentHash,
    String author,
    String createdAt,
    String message,
    String commitSha
) {
  public JsonObject toJson() {
    return Json.createObjectBuilder()
        .add("number", number)
        .add("name", name)
        .add("path", path)
        .add("contentHash", contentHash)
        .add("author", author == null ? "" : author)
        .add("createdAt", createdAt == null ? "" : createdAt)
        .add("message", message == null ? "" : message)
        .add("commitSha", commitSha)
        .build();
  }
}
