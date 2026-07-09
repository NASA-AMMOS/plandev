package gov.nasa.ammos.aerie.merlin.driver.test.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Querier;
import gov.nasa.jpl.aerie.merlin.protocol.model.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static gov.nasa.ammos.aerie.merlin.driver.test.framework.TestRegistrar.schedulerOfQuerier;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.ZERO;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Unit.UNIT;

public final class ModelActions {
  private ModelActions() {}

  public static void delay(Duration duration) {
    TestContext.get().threadedTask().thread().delay(duration);
  }

  public static void spawn(Runnable task) {
    final TestRegistrar.CellMap cells = TestContext.get().cells();
    TestContext.get().scheduler().spawn(InSpan.Fresh, x -> ThreadedTask.of(x, cells, () -> {
      task.run();
      return UNIT;
    }));
  }

  public static void call(Runnable task) {
    final TestRegistrar.CellMap cells = TestContext.get().cells();
    TestContext.get().threadedTask().thread().call(InSpan.Fresh, x -> ThreadedTask.of(x, cells, () -> {
      task.run();
      return UNIT;
    }));
  }

  public static void waitUntil(Function<Duration, Optional<Duration>> condition) {
    final var cells = TestContext.get().cells();
    TestContext.get().threadedTask().thread().waitUntil(
        new Condition() {
          @Override
          public Optional<Duration> nextSatisfied(final Querier now, final Duration atLatest) {
            return TestContext.set(
                new TestContext.Context(cells, schedulerOfQuerier(now), null),
                () -> condition.apply(atLatest));
          }
        });
  }

  public static void waitUntil(Supplier<Boolean> condition) {
    waitUntil($ -> condition.get() ? Optional.of(ZERO) : Optional.empty());
  }
}
