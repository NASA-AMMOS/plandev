package gov.nasa.ammos.aerie.merlin.driver.test.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Scheduler;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Responsible for enabling static methods to look up the simulator's scheduler and call methods on it
 */
public class TestContext {
  private static final ThreadLocal<Context> currentContext = new ThreadLocal<>();

  public record Context(TestRegistrar.CellMap cells, Scheduler scheduler, ThreadedTask<?> threadedTask) {}

  public static Context get() {
    return currentContext.get();
  }

  public static <T> T set(Context context, Supplier<T> supplier) {
    Objects.requireNonNull(context);
    currentContext.set(context);
    try {
      return supplier.get();
    } finally {
      currentContext.remove();
    }
  }

  /**
   * Sets the current context in this thread without automatic cleanup.
   * Caller is responsible for calling clearContext() when done.
   * This is thread-local, so each thread has its own context.
   */
  static void setContext(Context context) {
    currentContext.set(context);
  }

  /**
   * Clears the current context from this thread.
   */
  static void clearContext() {
    currentContext.remove();
  }
}
