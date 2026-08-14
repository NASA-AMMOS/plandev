package gov.nasa.jpl.aerie.foomissionmodel;

import gov.nasa.jpl.aerie.foomissionmodel.models.SimpleData;
import gov.nasa.jpl.aerie.merlin.framework.junit.MerlinExtension;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.extension.ExtendWith;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// The `@ExtendWith` annotation injects the given extension into JUnit's testing apparatus.
// Our `MerlinExtension` hooks test class construction and test method execution,
//   executing each with the appropriate simulation context.
@ExtendWith(MerlinExtension.class)
@TestInstance(Lifecycle.PER_CLASS)
public final class SimpleDataTest {
  // Initializers and the test class constructor are executed in an "initialization" Merlin context.
  // This means that models can be created (and cell storage allocated, and daemons spawned),
  //   but simulation control actions like `waitFor`, `delay`, and `emit` cannot be performed.
  private final SimpleData model = new SimpleData();

  // Test methods are executed in a "simulation" Merlin context.
  // This means that simulation control like `spawn`, `delay`, `waitFor`, and `emit` can be performed,
  // but additional cell storage cannot be allocated (and hence models cannot typically be constructed).
  @Test
  public void testTotalVolume() {
    // Within a Merlin test, simulation actions and test assertions can be mixed freely.
    model.a.activate();
    model.b.activate();
    delay(Duration.SECOND);

    assertThat(model.totalVolume.get()).isCloseTo(15.0, within(1e-9));
  }
}
