package gov.nasa.ammos.plandev.merlin.driver;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.jar.JarFile;

import gov.nasa.ammos.plandev.merlin.protocol.model.MerlinPlugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the pre-rename compatibility path against a real 4.3.0-era JAR.
 *
 * <p> The fixture cannot be produced from this source tree — its protocol references point
 * at {@code gov.nasa.jpl.aerie.merlin.protocol}, which no longer exists here — so it is
 * checked in. Rebuilding banananation and swapping it in defeats the entire test.
 */
public final class LegacyModelCompatTest {
  private static final Path LEGACY_JAR =
      Path.of(System.getProperty("legacyModelJar", "src/test/resources/banananation-4.3.0-legacy.jar"));

  /**
   * Documents the break this compat path exists for: the descriptor the loader looks up is
   * named after the interface's FQCN, and a 4.3 JAR simply does not contain that name.
   */
  @Test
  void aPreRenameJarDoesNotDeclareTheCurrentService() throws Exception {
    try (final var jar = new JarFile(LEGACY_JAR.toFile())) {
      assertNull(jar.getEntry("META-INF/services/" + MerlinPlugin.class.getCanonicalName()));
      assertNull(jar.getEntry("META-INF/services/gov.nasa.ammos.plandev.merlin.protocol.model.MerlinPlugin"));
    }
  }

  @Test
  void recognisesAPreRenameJar() throws Exception {
    assertTrue(LegacyModelCompat.isLegacy(LEGACY_JAR));
  }

  @Test
  void loadsAPreRenameModelAgainstTheCurrentProtocol() throws Exception {
    final var modelType = MissionModelLoader.loadModelType(LEGACY_JAR, "banananation", "4.3.0");

    // Crossing the protocol boundary at all is the point: the plugin was compiled against
    // interfaces that no longer exist under those names.
    assertEquals(
        "gov.nasa.jpl.aerie.banananation.generated.GeneratedModelType",
        modelType.getClass().getName(),
        "the model's own classes must keep their original names");

    final var directives = modelType.getDirectiveTypes().keySet();
    assertTrue(directives.contains("BiteBanana"), () -> "expected BiteBanana in " + directives);
    assertTrue(directives.contains("GrowBanana"), () -> "expected GrowBanana in " + directives);

    // Parameter metadata round-trips through ValueSchema, which is a remapped type.
    final var biteSize = modelType.getDirectiveTypes().get("BiteBanana").getInputType().getParameters();
    assertEquals(1, biteSize.size());
    assertEquals("biteSize", biteSize.get(0).name());

    // Configuration goes through InputType too.
    assertFalse(modelType.getConfigurationType().getParameters().isEmpty());
  }
}
