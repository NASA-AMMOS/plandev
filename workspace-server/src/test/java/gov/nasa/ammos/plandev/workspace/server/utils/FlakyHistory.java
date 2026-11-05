package gov.nasa.ammos.plandev.workspace.server.utils;

import gov.nasa.ammos.plandev.workspace.server.types.Workspace;
import gov.nasa.ammos.plandev.workspace.server.versioning.WorkspaceGitService;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import static gov.nasa.ammos.plandev.workspace.server.WorkspacePaths.internalDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A WorkspaceHistory whose commit step can be made to fail for chosen workspaces (deterministic injection). */
public final class FlakyHistory extends WorkspaceGitService {
  public final Set<Path> failCommitsIn = ConcurrentHashMap.newKeySet();

  /** Moves during drift healing fail if their destination is listed or ends with this (incident names vary). */
  public final Set<Path> failMovesTo = ConcurrentHashMap.newKeySet();
  public volatile String failMovesEndingWith;

  @Override
  public void move(final Path from, final Path to) throws IOException {
    if (failMovesTo.contains(to) || (failMovesEndingWith != null && to.endsWith(failMovesEndingWith))) {
      throw new IOException("injected move failure: " + to);
    }
    super.move(from, to);
  }

  @Override
  protected void commit(final Git git, final String message, final PersonIdent author) throws GitAPIException {
    try {
      if (failCommitsIn.contains(git.getRepository().getWorkTree().toPath().toRealPath())) {
        throw new GitAPIException("injected commit failure") {};
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    super.commit(git, message, author);
  }

  public static List<Path> incidents(final Workspace ws) throws IOException {
    final var dir = internalDirectory(ws).resolve("recovery");

    if (!Files.isDirectory(dir)) return List.of();
    try (final var entries = Files.list(dir)) {
      return entries.sorted().toList();
    }
  }

  public static void createMarker(final Path marker) throws IOException {
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, "interrupted");
  }

  public static String preserved(final Path incident, final Path path) throws IOException {
    return Files.readString(incident.resolve("files").resolve(path));
  }

  public static List<RevCommit> log(final Workspace ws) throws Exception {
    try (final var git = open(ws)) {
      final var commits = new ArrayList<RevCommit>();
      git.log().call().forEach(commits::add);
      return commits;
    }
  }

  public static RevCommit head(final Workspace ws) throws Exception {
    return log(ws).getFirst();
  }

  public static Set<String> headTree(final Workspace ws) throws Exception {
    try (final var git = open(ws); final var tw = new TreeWalk(git.getRepository())) {
      tw.addTree(head(ws).getTree());
      tw.setRecursive(true);
      final var paths = new TreeSet<String>();
      while (tw.next()) paths.add(tw.getPathString());
      return paths;
    }
  }

  public static List<DiffEntry> headDiff(final Workspace ws) throws Exception {
    try (final var git = open(ws); final var df = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
      df.setRepository(git.getRepository());
      df.setDetectRenames(true);
      final var headCommit = head(ws);
      return df.scan(headCommit.getParent(0).getTree(), headCommit.getTree());
    }
  }

  public static void assertClean(final Workspace ws) throws Exception {
    try (final var git = open(ws)) {
      assertEquals(Set.of(), dirtyPaths(git.status().call()), "workspace " + ws + " is dirty");
    }
  }
}
