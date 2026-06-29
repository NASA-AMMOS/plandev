package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.WorkspaceVersioningService.PullChoice;
import gov.nasa.jpl.aerie.workspace.server.WorkspaceVersioningService.PullDisposition;
import gov.nasa.jpl.aerie.workspace.server.WorkspaceVersioningService.PullItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 spike: inbound mirror pull-back, resolved file-by-file and never via a Git merge.
 *
 * <p>Retires the high-risk parts of the design: the 3-way classification against a recorded last-synced base,
 * adopting clean changes as new revisions (preserving the external author + a back-reference), surfacing
 * genuine conflicts and upstream deletions as take-mine/take-theirs, and — the headline property — advancing
 * the base so a resolved decision (e.g. a kept deletion) is not re-surfaced on the next pull.
 *
 * <p>The network transport (real push/fetch to a Git host) is out of spike scope; {@code establishMirrorBaseline}
 * and {@code stageIncoming} stand in for "PlanDev pushed, then an external writer committed and we fetched."
 */
class WorkspacePullbackSpikeTest {

  private WorkspaceFileSystemService fs;
  private WorkspaceVersioningService versioning;

  @BeforeEach
  void setUp() {
    fs = new WorkspaceFileSystemService(null);
    versioning = new WorkspaceVersioningService(null, fs);
  }

  private static void write(final Path file, final String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static Map<String, byte[]> change(final String path, final String content) {
    final var m = new HashMap<String, byte[]>();
    m.put(path, content == null ? null : content.getBytes(StandardCharsets.UTF_8)); // null content => delete
    return m;
  }

  /** Bring a one-file workspace into Git + establish the mirror base. */
  private void initWithFile(final Path root, final String path, final String content) throws Exception {
    write(root.resolve(path), content);
    versioning.migrate(root, "plandev");
    versioning.establishMirrorBaseline(root);
  }

  private PullDisposition dispositionOf(final List<PullItem> plan, final String path) {
    return plan.stream().filter(i -> i.path().equals(path)).map(PullItem::disposition).findFirst().orElse(null);
  }

  private String outcomeOf(final WorkspaceVersioningService.PullResult result, final String path) {
    return result.outcomes().stream().filter(o -> o.path().equals(path)).map(o -> o.outcome()).findFirst().orElse(null);
  }

  @Test
  void cleanAdoptTakesIncomingAndRecordsAnAdoptedRevision(@TempDir Path root) throws Exception {
    initWithFile(root, "a.txt", "A1");

    // External writer edits a.txt; PlanDev left it untouched.
    versioning.stageIncoming(root, change("a.txt", "A2"), "ext-bot", "bump a");

    final var plan = versioning.computePullPlan(root);
    assertEquals(PullDisposition.CLEAN_ADOPT, dispositionOf(plan, "a.txt"));

    final var result = versioning.applyPull(root, Map.of(), false, "plandev");
    assertEquals("ADOPTED", outcomeOf(result, "a.txt"));
    assertEquals("A2", Files.readString(root.resolve("a.txt")), "working copy converged to the incoming bytes");

    // The adopted change is the file's next revision, preserving the external author.
    final var revisions = versioning.listRevisions(root, Path.of("a.txt"));
    assertEquals(2, revisions.size());
    final var adopted = revisions.get(1);
    assertEquals("b", adopted.name());
    assertEquals("ext-bot", adopted.author(), "adopted revision preserves the external committer as author");
    assertEquals("bump a", adopted.message());

    // Base advanced — re-pulling with no new upstream change is a no-op.
    assertTrue(versioning.computePullPlan(root).isEmpty());
  }

  @Test
  void conflictKeepMineLeavesTheFileAndCreatesNoRevision(@TempDir Path root) throws Exception {
    initWithFile(root, "a.txt", "A1");
    versioning.stageIncoming(root, change("a.txt", "A-theirs"), "ext", "their edit");
    Files.writeString(root.resolve("a.txt"), "A-mine"); // PlanDev also edited it

    final var plan = versioning.computePullPlan(root);
    assertEquals(PullDisposition.CONFLICT, dispositionOf(plan, "a.txt"));

    final var result = versioning.applyPull(root, Map.of("a.txt", PullChoice.TAKE_MINE), false, "plandev");
    assertEquals("KEPT_MINE", outcomeOf(result, "a.txt"));
    assertEquals("A-mine", Files.readString(root.resolve("a.txt")));
    assertEquals(1, versioning.listRevisions(root, Path.of("a.txt")).size(), "take-mine creates no new revision");

    // The resolved conflict is not re-surfaced (base advanced); only a genuinely new upstream edit would.
    assertTrue(versioning.computePullPlan(root).isEmpty());
  }

  @Test
  void conflictTakeTheirsAdoptsIncoming(@TempDir Path root) throws Exception {
    initWithFile(root, "a.txt", "A1");
    versioning.stageIncoming(root, change("a.txt", "A-theirs"), "ext", "their edit");
    Files.writeString(root.resolve("a.txt"), "A-mine");

    assertEquals(PullDisposition.CONFLICT, dispositionOf(versioning.computePullPlan(root), "a.txt"));

    final var result = versioning.applyPull(root, Map.of("a.txt", PullChoice.TAKE_THEIRS), false, "plandev");
    assertEquals("ADOPTED", outcomeOf(result, "a.txt"));
    assertEquals("A-theirs", Files.readString(root.resolve("a.txt")));
    final var revisions = versioning.listRevisions(root, Path.of("a.txt"));
    assertEquals(2, revisions.size());
    assertEquals("ext", revisions.get(1).author());
  }

  @Test
  void upstreamDeletionKeptIsNotRePromptedNextPull(@TempDir Path root) throws Exception {
    initWithFile(root, "c.txt", "C1");
    versioning.stageIncoming(root, change("c.txt", null), "ext", "remove c"); // upstream deletes c.txt

    assertEquals(PullDisposition.DELETE, dispositionOf(versioning.computePullPlan(root), "c.txt"));

    // Reject the deletion (Keep): the file stays, and the decision is remembered.
    final var result = versioning.applyPull(root, Map.of(), false, "plandev");
    assertEquals("KEPT_MINE", outcomeOf(result, "c.txt"));
    assertTrue(Files.exists(root.resolve("c.txt")), "kept the file");

    // Headline property: pulling again with no new upstream change does NOT re-surface the deletion.
    assertTrue(versioning.computePullPlan(root).isEmpty(), "kept deletion must not be re-prompted");
  }

  @Test
  void upstreamDeletionAutoAcceptedSoftDeletes(@TempDir Path root) throws Exception {
    initWithFile(root, "c.txt", "C1");
    versioning.stageIncoming(root, change("c.txt", null), "ext", "remove c");

    final var result = versioning.applyPull(root, Map.of(), true /* autoAcceptDeletions */, "plandev");
    assertEquals("DELETED", outcomeOf(result, "c.txt"));
    assertFalse(Files.exists(root.resolve("c.txt")), "auto-accept soft-deletes the working copy");
  }

  @Test
  void upstreamAddIsAdoptedAsANewFileWithItsOwnHistory(@TempDir Path root) throws Exception {
    initWithFile(root, "a.txt", "A1");
    versioning.stageIncoming(root, change("nested/d.txt", "D1"), "ext", "add d");

    assertEquals(PullDisposition.CLEAN_ADOPT, dispositionOf(versioning.computePullPlan(root), "nested/d.txt"));

    versioning.applyPull(root, Map.of(), false, "plandev");
    assertEquals("D1", Files.readString(root.resolve("nested/d.txt")));
    final var revisions = versioning.listRevisions(root, Path.of("nested/d.txt"));
    assertEquals(1, revisions.size());
    assertEquals("a", revisions.get(0).name());
    assertEquals("ext", revisions.get(0).author());
    assertFalse(revisions.get(0).fileId().isBlank(), "adopted new file gets a stable fileId");
  }

  @Test
  void nothingFetchedMeansEmptyPlan(@TempDir Path root) throws Exception {
    write(root.resolve("a.txt"), "A1");
    versioning.migrate(root, "plandev"); // migrated, but no mirror established / nothing fetched
    assertTrue(versioning.computePullPlan(root).isEmpty());

    // After linking, with no external changes, the plan is still empty (base == incoming).
    versioning.establishMirrorBaseline(root);
    assertTrue(versioning.computePullPlan(root).isEmpty());
  }
}
