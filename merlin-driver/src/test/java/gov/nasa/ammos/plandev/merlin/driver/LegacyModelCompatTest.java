package gov.nasa.ammos.plandev.merlin.driver;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
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

  /**
   * The invariant the whole compat path rests on: everything a 4.3 JAR asks of this runtime
   * is still there. Checked against a reference set recorded from the full 4.3 artifact
   * rather than the trimmed fixture, which carries only a subset -- see the baseline header.
   *
   * <p> When this fails the 4.3 window has closed. The answer is to refuse the vintage with
   * a message naming what is missing, not to widen the remap.
   */
  @Test
  void thisRuntimeStillSatisfiesTheRecorded43Abi() throws Exception {
    final var baseline = Files.readAllLines(Path.of("src/test/resources/abi-4.3.0-model.txt"));
    final var unsatisfied = LegacyAbiCheck.unsatisfied(
        baseline, LegacyModelCompat.redirect(), getClass().getClassLoader());
    assertTrue(unsatisfied.isEmpty(),
        () -> "the protocol has drifted from what 4.3 model JARs were compiled against:\n  "
            + unsatisfied.stream().map(Object::toString).collect(java.util.stream.Collectors.joining("\n  ")));
  }

  /** Guards the baseline itself: regenerating it from a trimmed fixture would gut the check. */
  @Test
  void theBaselineCoversMoreThanTheTrimmedFixture() throws Exception {
    final var baseline = Files.readAllLines(Path.of("src/test/resources/abi-4.3.0-model.txt"))
        .stream().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
    final var fromFixture = LegacyAbiCheck.references(LEGACY_JAR, LegacyModelCompat.redirect());
    assertTrue(baseline.containsAll(fromFixture), "baseline is missing references the fixture makes");
    assertTrue(baseline.size() > fromFixture.size(),
        () -> "baseline (" + baseline.size() + ") should exceed the fixture's own surface ("
            + fromFixture.size() + "); was it regenerated from the fixture?");
  }

  /** A check that has never failed is not known to work. */
  @Test
  void theAbiCheckReportsAReferenceTheRuntimeDoesNotHave() throws Exception {
    final var jar = Files.createTempFile("drifted", ".jar");
    try (final var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new java.util.zip.ZipEntry("example/Caller.class"));
      out.write(callerReferencing("gov/nasa/jpl/aerie/merlin/protocol/types/Duration",
                                  "aMethodThatWasRemoved", "()V"));
      out.closeEntry();
    }
    final var unsatisfied = LegacyAbiCheck.unsatisfiedReferences(
        jar, LegacyModelCompat.redirect(), getClass().getClassLoader());
    assertEquals(1, unsatisfied.size(), () -> String.valueOf(unsatisfied));
    assertEquals("aMethodThatWasRemoved", unsatisfied.get(0).member());
    assertEquals("no such method", unsatisfied.get(0).reason());
  }

  /** A class whose only content is one call into {@code owner}. */
  private static byte[] callerReferencing(final String owner, final String name, final String descriptor) {
    final var writer = new org.objectweb.asm.ClassWriter(0);
    writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                 "example/Caller", null, "java/lang/Object", null);
    final var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "call", "()V", null, null);
    method.visitCode();
    method.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, owner, name, descriptor, false);
    method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
    method.visitMaxs(0, 0);
    method.visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }
}
