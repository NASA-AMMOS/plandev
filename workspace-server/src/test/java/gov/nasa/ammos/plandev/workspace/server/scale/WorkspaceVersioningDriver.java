package gov.nasa.ammos.plandev.workspace.server.scale;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The product-level operations the scale harness drives. Scenarios only ever talk to this interface; everything about
 * how a backend stores history, revisions, identities or indexes lives in an adapter.
 *
 * <p>Workspaces, revisions and remotes are opaque string handles. Paths are '/'-separated and workspace-relative.
 * Tokens are the concurrency tokens a client would hold (ETag / If-Match).
 */
public interface WorkspaceVersioningDriver extends AutoCloseable {
  enum Feature {
    /** {@link #importWorkspace}: create a workspace whose initial content already exists (a single import). */
    IMPORT,
    MOVE,
    LAST_EDIT,
    /** {@link #restart}: drop all in-process state and reopen, as a server restart would. */
    RESTART,
    /** {@link #rebuild}: regenerate derived revision/index state from the authoritative store. */
    REBUILD,
    /** {@link #createRemote}, {@link #publish}, {@link #cloneWorkspace}, {@link #synchronize}. */
    REMOTE
  }

  /** A save or restore was rejected because the client's token is stale. Nothing changed. */
  final class Conflict extends Exception {
    public Conflict(final String message) {
      super(message);
    }
  }

  record FileState(byte[] content, String token) {}

  record RevisionInfo(String id, String name, Instant createdAt) {}

  /**
   * A file's revisions, oldest first. {@code matchingId} is the newest revision equal to the file's current state.
   * {@code restoreToken} is the token {@link #restoreRevision} must be given.
   */
  record RevisionListing(List<RevisionInfo> revisions, Optional<String> matchingId, String restoreToken) {}

  record LastEdit(String by, String at) {}

  /** Adapter name and configuration, recorded with the results. */
  Map<String, Object> describe();

  Set<Feature> features();

  String createWorkspace(String name) throws Exception;

  String importWorkspace(String name, Map<String, byte[]> files) throws Exception;

  /** Create or replace a file. {@code ifMatch} null means unconditional. Returns the new token. */
  String write(String ws, String path, byte[] content, String ifMatch) throws Exception;

  FileState read(String ws, String path) throws Exception;

  boolean exists(String ws, String path) throws Exception;

  void delete(String ws, String path) throws Exception;

  void move(String ws, String from, String to) throws Exception;

  RevisionInfo createRevision(String ws, String path) throws Exception;

  RevisionListing listRevisions(String ws, String path) throws Exception;

  /** A revision's file content (preview). */
  byte[] readRevision(String ws, String revisionId) throws Exception;

  /** Make a revision the file's current state. Returns the file's new token. */
  String restoreRevision(String ws, String path, String revisionId, String ifMatch) throws Exception;

  LastEdit lastEdit(String ws, String path) throws Exception;

  void restart() throws Exception;

  void rebuild(String ws) throws Exception;

  String createRemote(String name) throws Exception;

  void publish(String ws, String remote) throws Exception;

  String cloneWorkspace(String name, String remote) throws Exception;

  void synchronize(String ws, String remote) throws Exception;

  /**
   * Implementation-specific storage numbers (bytes on disk, object/ref counts...). Diagnostics only: scenarios never
   * decide anything from them. Should include {@code diskBytes}, the workspace's total footprint, when known.
   */
  Map<String, Object> diagnostics(String ws) throws Exception;

  /** Run whatever repository maintenance is available (manually, as a diagnostic) and report its effect. */
  Map<String, Object> maintenance(String ws) throws Exception;
}
