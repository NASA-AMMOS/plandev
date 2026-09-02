package gov.nasa.ammos.plandev.merlin.server.models;

import gov.nasa.ammos.plandev.merlin.driver.LegacyAbiCheck;
import gov.nasa.ammos.plandev.merlin.driver.LegacyProcedureCompat;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Constraint procedures across the package rename.
 *
 * <p> The legacy JAR is checked in because it cannot be produced from this tree: its
 * references point at packages that no longer exist here. The current JAR is built by
 * gradle and handed over as a system property, so it always matches this source. *
 * <p> The fixture is trimmed: the classes a load actually touches, plus the stale bundled
 * copies those classes reference. Keeping the stale copies matters -- they are what has to
 * stay shadowed, so a fixture without them would pass for the wrong reason.
 */
public final class LegacyProcedureLoadTest {
  private static final Path LEGACY = Path.of("src/test/resources/ConstFruit-4.3.0-legacy.jar");
  private static final Path CURRENT = Path.of(System.getProperty("currentProcedureJar", ""));

  @Test
  void recognisesEachVintage() throws Exception {
    assertTrue(LegacyProcedureCompat.isLegacy(LEGACY));
    assertFalse(LegacyProcedureCompat.isLegacy(CURRENT), "a freshly built procedure is not legacy");
  }

  /**
   * Trimming left this fixture without the marker the sniff looks for first, so it reaches
   * the fallback scan -- which is worth keeping, because it is the branch a JAR shaped
   * unlike the ones we know would take. Its counterpart in the scheduler covers the fast
   * path.
   */
  @Test
  void recognisesALegacyJarWithoutTheFastPathMarker() throws Exception {
    try (final var jar = new java.util.jar.JarFile(LEGACY.toFile())) {
      assertNull(jar.getEntry("gov/nasa/jpl/aerie/merlin/protocol/types/Duration.class"));
    }
    assertTrue(LegacyProcedureCompat.isLegacy(LEGACY));
  }

  @Test
  void loadsAPreRenameConstraintProcedure() throws Exception {
    final var mapper = ProcedureLoader.loadProcedure(LEGACY);

    // The mapper crossing the boundary at all is the point: it was compiled against a
    // ProcedureMapper that no longer exists under that name.
    assertEquals("StructSchema[value={}]", mapper.valueSchema().toString());
    assertTrue(mapper.getInputType().getParameters().isEmpty());

    // The procedure's own package sits *under* the renamed API root, and must not have
    // been rewritten along with it -- otherwise Main-Class would no longer resolve.
    assertTrue(
        mapper.getClass().getName().startsWith("gov.nasa.ammos.aerie.procedural.examples."),
        () -> "unexpected: " + mapper.getClass().getName());
  }

  @Test
  void stillLoadsACurrentConstraintProcedure() throws Exception {
    final var mapper = ProcedureLoader.loadProcedure(CURRENT);
    assertEquals("StructSchema[value={}]", mapper.valueSchema().toString());
    assertTrue(mapper.getClass().getName().startsWith("gov.nasa.ammos.plandev.procedural.examples."));
  }

  @Test
  void bothVintagesYieldTheSameSchema() throws Exception {
    assertEquals(
        ProcedureLoader.loadProcedure(CURRENT).valueSchema().toString(),
        ProcedureLoader.loadProcedure(LEGACY).valueSchema().toString());
  }

  @Test
  void aJarThatIsNoProcedureFailsCleanly() throws Exception {
    final var empty = Files.createTempFile("not-a-procedure", ".jar");
    try (final var out = new java.util.jar.JarOutputStream(Files.newOutputStream(empty))) {
      out.putNextEntry(new java.util.zip.ZipEntry("nothing.txt"));
      out.write("hi".getBytes());
    }
    // No Main-Class: a ProcedureLoadException, never a raw NullPointerException.
    assertThrows(ProcedureLoader.ProcedureLoadException.class, () -> ProcedureLoader.loadProcedure(empty));
    assertDoesNotThrow(() -> LegacyProcedureCompat.isLegacy(empty));
    assertFalse(LegacyProcedureCompat.isLegacy(empty));
  }

  /**
   * The same invariant for procedures, resolved against the classes this service actually
   * runs, and checked against the full 4.3 artifact's reference set rather than the trimmed
   * fixture's subset.
   */
  @Test
  void thisRuntimeStillSatisfiesTheRecorded43Abi() throws Exception {
    final var runtime = getClass().getClassLoader();
    final var baseline = Files.readAllLines(Path.of("src/test/resources/abi-4.3.0-constraint.txt"));
    final var unsatisfied = LegacyAbiCheck.unsatisfied(
        baseline, LegacyProcedureCompat.redirect(runtime), runtime);
    assertTrue(unsatisfied.isEmpty(),
        () -> "the API has drifted from what 4.3 procedure JARs were compiled against:\n  "
            + unsatisfied.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n  ")));
  }

  /** Guards the baseline itself: regenerating it from a trimmed fixture would gut the check. */
  @Test
  void theBaselineCoversMoreThanTheTrimmedFixture() throws Exception {
    final var runtime = getClass().getClassLoader();
    final var baseline = Files.readAllLines(Path.of("src/test/resources/abi-4.3.0-constraint.txt"))
        .stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
    final var fromFixture = LegacyAbiCheck.references(LEGACY, LegacyProcedureCompat.redirect(runtime));
    assertTrue(baseline.containsAll(fromFixture), "baseline is missing references the fixture makes");
    assertTrue(baseline.size() > fromFixture.size(),
        () -> "baseline (" + baseline.size() + ") should exceed the fixture's own surface ("
            + fromFixture.size() + "); was it regenerated from the fixture?");
  }
}
