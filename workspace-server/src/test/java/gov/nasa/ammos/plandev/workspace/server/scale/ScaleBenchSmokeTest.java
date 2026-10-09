package gov.nasa.ammos.plandev.workspace.server.scale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every scenario at toy sizes, so the harness keeps working as the backend changes. Measures nothing useful. */
class ScaleBenchSmokeTest {
  @TempDir Path tmp;

  @Test
  void everyScenarioRunsAndVerifies() throws Exception {
    assertTrue(ScaleBench.run("profile=smoke", "out=" + tmp.resolve("out")));
  }
}
