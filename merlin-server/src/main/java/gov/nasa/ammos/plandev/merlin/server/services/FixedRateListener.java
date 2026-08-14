package gov.nasa.jpl.aerie.merlin.server.services;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public interface FixedRateListener<T> extends AutoCloseable {
  void updateValue(T t);

  @Override
  void close();

  /**
   * Call reporter.accept at fixed intervals using the given period. The value passed to the consumer is the
   * most recent argument to `updateValue`.
   *
   * @param reporter The Consumer to call periodically
   * @param initialValue The initial value to pass to the given Consumer
   * @param periodMillis The interval at which to call reporter.accept
   * @return a FixedRateListener
   */
  static <T> FixedRateListener<T> callAtFixedRate(final Consumer<T> reporter, final T initialValue, final long periodMillis) {
    final var latestValue = new AtomicReference<>(initialValue);

    final var executor = new ScheduledThreadPoolExecutor(1);
    final var task = executor.scheduleAtFixedRate(
        () -> reporter.accept(latestValue.get()),
        0,
        periodMillis,
        TimeUnit.MILLISECONDS);

    return new FixedRateListener<>() {
      @Override
      public void updateValue(final T t) {
        latestValue.set(t);
      }

      @Override
      public void close() {
          task.cancel(false);
          executor.shutdown();
      }
    };
  }
}
