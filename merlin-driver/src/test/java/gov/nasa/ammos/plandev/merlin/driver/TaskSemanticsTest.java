package gov.nasa.jpl.aerie.merlin.driver;

import gov.nasa.jpl.aerie.contrib.models.Register;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(MerlinExtension.class)
public final class TaskSemanticsTest {
  private final Register<Integer> register = Register.forImmutable(1);

  @Test
  @DisplayName("All child tasks' effects are visible on resumption from call()")
  void simulationEngineWaitsForChildren() {
    assertEquals(1, register.get());
    assertTrue(!register.isConflicted());

    call(() -> {
      spawn(() -> register.set(2));
      spawn(() -> register.set(3));
    });

    assertAll(List.of(
        () -> assertEquals(1, register.get(), "The register should equal 1"),
        () -> assertTrue(register.isConflicted(), "The register should be in a conflicted state")
    ));
  }
}
