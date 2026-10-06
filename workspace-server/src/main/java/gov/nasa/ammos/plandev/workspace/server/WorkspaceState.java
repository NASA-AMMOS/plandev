package gov.nasa.ammos.plandev.workspace.server;

import gov.nasa.ammos.plandev.workspace.server.postgres.RenderType;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonValue;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Non-versioned, per-file runtime state of a workspace, persisted at {@code <root>/.seqdev/state.json}.
 *
 * <p>File metadata is split three ways:
 * <ul>
 *   <li><b>Versioned</b> ({@code .meta.seqdev} sidecar, committed to Git): {@code version}, {@code createdBy},
 *       {@code createdAt}, {@code user}.</li>
 *   <li><b>Derived</b> (from Git history, see {@link WorkspaceHistory#lastEdits}): {@code lastEditedBy},
 *       {@code lastEditedAt}.</li>
 *   <li><b>Runtime</b> (this file): {@code readOnly}, which is present-day policy rather than file history, plus the
 *       pre-history {@code lastEdited*} values captured when a workspace was first put under history.</li>
 * </ul>
 *
 * <p>This file is excluded from Git, is never part of the versioned workspace, and is reserved from the file API
 * ({@link WorkspacePaths}). It is only written while the workspace mutation lock is held; {@link WorkspaceHistory}
 * snapshots and restores it alongside the working tree when a mutation rolls back. Writes are atomic (temp + rename)
 * so lock-free readers never observe a partial file.
 *
 * <p>Keys are workspace-relative, '/'-separated paths of content files (not sidecars).
 */
final class WorkspaceState {
  record Entry(Boolean readOnly, String legacyLastEditedBy, String legacyLastEditedAt) {
    boolean isEmpty() {
      return readOnly == null && legacyLastEditedBy == null && legacyLastEditedAt == null;
    }
  }

  private static final int VERSION = 1;
  private static final List<String> LEGACY_KEYS = List.of("readOnly", "lastEditedBy", "lastEditedAt");

  private final Path root;
  private final TreeMap<String, Entry> files;

  private WorkspaceState(final Path root, final TreeMap<String, Entry> files) {
    this.root = root;
    this.files = files;
  }

  static Path file(final Path root) {
    return root.resolve(WorkspacePaths.STATE_DIR).resolve("state.json");
  }

  /**
   * Load the state, or an empty state if none has been written yet. Anything else that is not exactly the current
   * schema (a non-regular file, malformed JSON, another version, wrongly typed fields) is an IOException: runtime state
   * holds locks, so it is never silently read as "no state".
   */
  static WorkspaceState load(final Path root) throws IOException {
    final var files = new TreeMap<String, Entry>();
    final var path = file(root);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new WorkspaceState(root, files);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Workspace runtime state at " + path + " is not a regular file");
    }
    try (final var reader = Json.createReader(new StringReader(Files.readString(path)))) {
      final var json = reader.readObject();
      if (!(json.get("version") instanceof JsonNumber v) || !v.isIntegral() || v.intValue() != VERSION) {
        throw new IllegalArgumentException("unsupported version " + json.get("version"));
      }
      for (final var e : json.getJsonObject("files").entrySet()) {
        final var o = e.getValue().asJsonObject();
        files.put(e.getKey(), new Entry(
            optional(o, "readOnly", JsonValue.ValueType.TRUE, JsonValue.ValueType.FALSE) == null ? null : o.getBoolean("readOnly"),
            optional(o, "legacyLastEditedBy", JsonValue.ValueType.STRING) == null ? null : o.getString("legacyLastEditedBy"),
            optional(o, "legacyLastEditedAt", JsonValue.ValueType.STRING) == null ? null : o.getString("legacyLastEditedAt")));
      }
    } catch (JsonException | ClassCastException | NullPointerException | IllegalArgumentException e) {
      throw new IOException("Workspace runtime state at " + path + " is malformed: " + e.getMessage(), e);
    }
    return new WorkspaceState(root, files);
  }

  /** The field's value if present (and of one of the given types), null if absent; any other type is malformed. */
  private static JsonValue optional(final JsonObject o, final String key, final JsonValue.ValueType... types) {
    final var value = o.get(key);
    if (value == null || Arrays.asList(types).contains(value.getValueType())) return value;
    throw new IllegalArgumentException("'%s' has type %s".formatted(key, value.getValueType()));
  }

  void save() throws IOException {
    final var filesJson = Json.createObjectBuilder();
    files.forEach((key, entry) -> {
      final var o = Json.createObjectBuilder();
      if (entry.readOnly() != null) o.add("readOnly", entry.readOnly());
      if (entry.legacyLastEditedBy() != null) o.add("legacyLastEditedBy", entry.legacyLastEditedBy());
      if (entry.legacyLastEditedAt() != null) o.add("legacyLastEditedAt", entry.legacyLastEditedAt());
      filesJson.add(key, o);
    });
    final var json = Json.createObjectBuilder().add("version", VERSION).add("files", filesJson).build().toString();
    WorkspacePaths.writeAtomically(root, file(root), json.getBytes(StandardCharsets.UTF_8));
  }

  Optional<Entry> entry(final String key) {
    return Optional.ofNullable(files.get(key));
  }

  Optional<Boolean> readOnly(final String key) {
    return entry(key).map(Entry::readOnly);
  }

  /** Set (or with {@code null}, unset) a file's readOnly flag. */
  void setReadOnly(final String key, final Boolean readOnly) {
    final var old = files.getOrDefault(key, new Entry(null, null, null));
    put(key, new Entry(readOnly, old.legacyLastEditedBy(), old.legacyLastEditedAt()));
  }

  /** Forget everything about a path and, if it is a directory, everything beneath it. */
  void remove(final String key) {
    files.keySet().removeIf(k -> isAtOrUnder(k, key));
  }

  /** Re-key a path (and everything beneath it) after a move or rename within the workspace. */
  void rekey(final String from, final String to) {
    final var moved = new TreeMap<String, Entry>();
    files.entrySet().removeIf(e -> {
      if (!isAtOrUnder(e.getKey(), from)) return false;
      moved.put(to + e.getKey().substring(from.length()), e.getValue());
      return true;
    });
    files.putAll(moved);
  }

  /** Keys of read-only files at or beneath {@code key} ("" means the whole workspace). */
  List<String> readOnlyAtOrUnder(final String key) {
    final var out = new ArrayList<String>();
    files.forEach((k, e) -> {
      if (Boolean.TRUE.equals(e.readOnly()) && isAtOrUnder(k, key)) out.add(k);
    });
    return out;
  }

  /** Explicit readOnly flags (true or false) at or beneath {@code key}. */
  Map<String, Boolean> readOnlyFlagsAtOrUnder(final String key) {
    final var out = new TreeMap<String, Boolean>();
    files.forEach((k, e) -> {
      if (e.readOnly() != null && isAtOrUnder(k, key)) out.put(k, e.readOnly());
    });
    return out;
  }

  private void put(final String key, final Entry entry) {
    if (entry.isEmpty()) files.remove(key);
    else files.put(key, entry);
  }

  private static boolean isAtOrUnder(final String key, final String prefix) {
    return prefix.isEmpty() || key.equals(prefix) || key.startsWith(prefix + "/");
  }

  /**
   * Migrate sidecars that predate the versioned/runtime split: move {@code readOnly} and {@code lastEdited*} out of
   * every well-formed sidecar that still has them into this state file, then rewrite that sidecar with only its
   * versioned fields. Idempotent and resumable: completion is "no sidecar still has a legacy field", never "the state
   * file exists". The state file is written before any sidecar is rewritten, and every write is an atomic replace, so
   * an interrupted run leaves each sidecar either legacy (finished by the next run) or fully migrated, never partial;
   * sidecars without legacy fields and malformed sidecars are left untouched. The caller holds the workspace lock.
   */
  static void migrateFromSidecars(final Path root) throws IOException {
    final var state = load(root);
    final var rewrites = new TreeMap<Path, String>();

    for (final var path : WorkspacePaths.walk(root, Integer.MAX_VALUE)) {
      final var name = path.getFileName().toString();
      if (!Files.isRegularFile(path) || !RenderType.isAerieMetadataFile(name)) continue;

      final JsonObject sidecar;
      try (final var reader = Json.createReader(new StringReader(Files.readString(path)))) {
        sidecar = reader.readObject();
      } catch (JsonException | IOException e) {
        continue;
      }
      if (LEGACY_KEYS.stream().noneMatch(sidecar::containsKey)) continue;

      final var contentName = name.substring(1, name.length() - RenderType.aerieMetadataExtension.length());
      final var key = WorkspacePaths.key(root, path.resolveSibling(contentName));
      final var rawReadOnly = sidecar.get("readOnly");
      final var readOnlyType = rawReadOnly == null ? null : rawReadOnly.getValueType();
      final Boolean readOnly = readOnlyType == JsonValue.ValueType.TRUE ? Boolean.TRUE
          : readOnlyType == JsonValue.ValueType.FALSE ? Boolean.FALSE : null;
      // The sidecar still holding legacy fields means it was never rewritten, so its values are the source of truth.
      state.put(key, new Entry(readOnly, stringOrNull(sidecar, "lastEditedBy"), stringOrNull(sidecar, "lastEditedAt")));

      rewrites.put(path, WorkspaceFileSystemService.serializeSidecar(
          stringOrNull(sidecar, "version"),
          stringOrNull(sidecar, "createdBy"),
          stringOrNull(sidecar, "createdAt"),
          sidecar.get("user") instanceof JsonObject user ? user : null));
    }

    state.save();
    for (final var rewrite : rewrites.entrySet()) {
      WorkspacePaths.writeAtomically(root, rewrite.getKey(), rewrite.getValue().getBytes(StandardCharsets.UTF_8));
    }
  }

  private static String stringOrNull(final JsonObject o, final String key) {
    return o.get(key) instanceof javax.json.JsonString s ? s.getString() : null;
  }
}
