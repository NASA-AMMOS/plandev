package gov.nasa.ammos.plandev.workspace.server;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single source of truth for which names inside a workspace are internal to the workspace server.
 *
 * <ul>
 *   <li>{@code .git} holds the workspace's history repository (see {@link WorkspaceHistory}).</li>
 *   <li>{@code .gitignore}, {@code .gitattributes} and {@code .gitmodules} would change what Git tracks or how it
 *       rewrites file bytes, which would let a "clean" repository silently omit or alter user files. They are reserved
 *       in Phase 1; {@code .gitignore} may be relaxed once Git remote interoperability exists.</li>
 *   <li>{@code .seqdev} holds non-versioned runtime state (see {@link WorkspaceState}). It is never committed and is
 *       not part of the versioned workspace.</li>
 * </ul>
 *
 * Reserved names are rejected at every path segment, case-insensitively, by
 * {@link WorkspaceFileSystemService#resolveReadingPath}, so no user-facing file API can read or write them.
 * {@code .meta.seqdev} sidecars are SeqDev-managed but are not reserved here: they are addressed through the
 * metadata API, and the file API handlers reject them directly.
 */
public final class WorkspacePaths {
  private WorkspacePaths() {}

  public static final String GIT_DIR = ".git";
  public static final String STATE_DIR = ".seqdev";

  private static final Set<String> RESERVED = Set.of(GIT_DIR, ".gitignore", ".gitattributes", ".gitmodules", STATE_DIR);

  public static boolean isReservedName(final String segment) {
    return RESERVED.contains(segment.toLowerCase(Locale.ROOT));
  }

  /** The first reserved segment of a workspace-relative path, or null if it has none. */
  public static String firstReservedSegment(final Path relativePath) {
    for (final var segment : relativePath) {
      if (isReservedName(segment.toString())) return segment.toString();
    }
    return null;
  }

  /** A workspace-relative path as a '/'-separated key (the form Git and {@link WorkspaceState} use). */
  public static String key(final Path root, final Path absolutePath) {
    final var rel = root.normalize().relativize(absolutePath.normalize());
    final var parts = new ArrayList<String>();
    for (final var segment : rel) parts.add(segment.toString());
    return String.join("/", parts);
  }

  /**
   * Like {@code Files.walk(dir, maxDepth)} minus the starting directory, but never descends into (or returns) a
   * reserved name, so Git internals and runtime state never leak into listings, copies or scans.
   */
  public static List<Path> walk(final Path dir, final int maxDepth) throws IOException {
    final var out = new ArrayList<Path>();
    Files.walkFileTree(dir, EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(final Path d, final BasicFileAttributes attrs) {
        if (d.equals(dir)) return FileVisitResult.CONTINUE;
        if (isReservedName(d.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
        out.add(d);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(final Path f, final BasicFileAttributes attrs) {
        // Directories at maxDepth arrive here instead of preVisitDirectory
        if (!isReservedName(f.getFileName().toString())) out.add(f);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFileFailed(final Path f, final IOException exc) throws IOException {
        throw exc;
      }
    });
    return out;
  }
}
