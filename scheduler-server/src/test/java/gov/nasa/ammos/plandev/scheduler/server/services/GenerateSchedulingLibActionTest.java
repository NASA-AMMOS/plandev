package gov.nasa.jpl.aerie.scheduler.server.services;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public final class GenerateSchedulingLibActionTest {

  @Test
  void testTypescriptResourcesLoaded() {
    assertTypescriptResourceLoaded("scheduler-edsl-fluent-api.ts");
    assertTypescriptResourceLoaded("scheduler-ast.ts");
    assertTypescriptResourceLoaded("constraints-edsl-fluent-api.ts");
    assertTypescriptResourceLoaded("constraints-ast.ts");
    assertTypescriptResourceLoaded("constraints/TemporalPolyfillTypes.ts");
  }

  private static void assertTypescriptResourceLoaded(final String basename) {
    final var res = GenerateSchedulingLibAction.getTypescriptResource(basename);
    assertEquals(basename, res.basename());
    assertFalse(res.source().isEmpty());
  }
}
