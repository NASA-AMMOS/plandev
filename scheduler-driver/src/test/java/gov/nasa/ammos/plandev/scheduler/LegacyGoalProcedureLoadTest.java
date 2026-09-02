package gov.nasa.ammos.plandev.scheduler;

import gov.nasa.ammos.plandev.merlin.driver.LegacyProcedureCompat;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scheduling half of the same story. Worth its own test rather than a parameter on the
 * constraints one: the two services ship different libraries, and the redirect is decided
 * against whatever the running service actually has -- merlin-server carries
 * procedural:constraints and no scheduling, and the scheduler is the other way round. *
 * <p> The fixture is trimmed: the classes a load actually touches, plus the stale bundled
 * copies those classes reference. Keeping the stale copies matters -- they are what has to
 * stay shadowed, so a fixture without them would pass for the wrong reason.
 */
public final class LegacyGoalProcedureLoadTest {
  private static final Path LEGACY = Path.of("src/test/resources/SampleProcedure-4.3.0-legacy.jar");
  private static final Path CURRENT = Path.of(System.getProperty("currentGoalJar", ""));

  @Test
  void recognisesEachVintage() throws Exception {
    assertTrue(LegacyProcedureCompat.isLegacy(LEGACY));
    assertFalse(LegacyProcedureCompat.isLegacy(CURRENT));
  }

  /** This fixture kept the marker, so it covers the sniff's fast path. */
  @Test
  void recognisesALegacyJarByTheFastPathMarker() throws Exception {
    try (final var jar = new java.util.jar.JarFile(LEGACY.toFile())) {
      assertNotNull(jar.getEntry("gov/nasa/jpl/aerie/merlin/protocol/types/Duration.class"));
    }
    assertTrue(LegacyProcedureCompat.isLegacy(LEGACY));
  }

  @Test
  void loadsAPreRenameGoalProcedure() throws Exception {
    final var mapper = ProcedureLoader.loadProcedure(LEGACY);
    final var parameters = mapper.getInputType().getParameters();
    assertEquals(1, parameters.size());
    assertEquals("quantity", parameters.get(0).name());
    assertTrue(mapper.getClass().getName().startsWith("gov.nasa.ammos.aerie.procedural.examples."));
  }

  @Test
  void stillLoadsACurrentGoalProcedure() throws Exception {
    final var mapper = ProcedureLoader.loadProcedure(CURRENT);
    assertEquals("quantity", mapper.getInputType().getParameters().get(0).name());
    assertTrue(mapper.getClass().getName().startsWith("gov.nasa.ammos.plandev.procedural.examples."));
  }

  @Test
  void bothVintagesAgreeOnTheParameterSchema() throws Exception {
    assertEquals(
        ProcedureLoader.loadProcedure(CURRENT).getInputType().getParameters().toString(),
        ProcedureLoader.loadProcedure(LEGACY).getInputType().getParameters().toString());
  }
}
