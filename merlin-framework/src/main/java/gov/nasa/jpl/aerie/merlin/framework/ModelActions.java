package gov.nasa.jpl.aerie.merlin.framework;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.TaskFactory;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.InSpan;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;

import java.util.function.Supplier;

public /*non-final*/ class ModelActions {
  protected ModelActions() {}

  /* package-local */
  static final Scoped<Context> context = Scoped.create();


  public static <T> TaskFactory<T> threaded(final Supplier<T> task) {
    return executor -> new ThreadedTask<>(executor, ModelActions.context, task);
  }

  public static TaskFactory<Unit> threaded(final Runnable task) {
    return threaded(() -> {
      task.run();
      return Unit.UNIT;
    });
  }

  public static <T> TaskFactory<T> replaying(final Supplier<T> task) {
    return executor -> new ReplayingTask<>(ModelActions.context, task);
  }

  public static TaskFactory<Unit> replaying(final Runnable task) {
    return replaying(() -> {
      task.run();
      return Unit.UNIT;
    });
  }


  public static <T> void emit(final T event, final Topic<T> topic) {
    context.get().emit(event, topic);
  }


  public static <T> void spawn(final Supplier<T> task) {
    spawn(threaded(task));
  }

  public static <T> void spawn(String taskName, final Supplier<T> task) {
    spawn(taskName, threaded(task));
  }

  public static void spawn(final Runnable task) {
    spawn(() -> {
      task.run();
      return Unit.UNIT;
    });
  }

  public static <T> void spawn(final TaskFactory<T> task) {
    context.get().spawn(InSpan.Parent, task);
  }

  public static <T> void spawn(final String taskName, final TaskFactory<T> task) {
    context.get().spawn(taskName, InSpan.Parent, task);
  }

  public static void call(final Runnable task) {
    call(threaded(task));
  }

  public static <T> void call(final Supplier<T> task) {
    call(threaded(task));
  }

  public static <T> void call(final TaskFactory<T> task) {
    context.get().call(InSpan.Parent, task);
  }


  public static <T> void spawnWithSpan(final Supplier<T> task) {
    spawnWithSpan(threaded(task));
  }

  public static void spawnWithSpan(final Runnable task) {
    spawnWithSpan(() -> {
      task.run();
      return Unit.UNIT;
    });
  }

  public static <T> void spawnWithSpan(final TaskFactory<T> task) {
    context.get().spawn(InSpan.Fresh, task);
  }

  public static void callWithSpan(final Runnable task) {
    callWithSpan(threaded(task));
  }

  public static <T> void callWithSpan(final Supplier<T> task) {
    callWithSpan(threaded(task));
  }

  public static <T> void callWithSpan(final TaskFactory<T> task) {
    context.get().call(InSpan.Fresh, task);
  }

  public static void defer(final Duration duration, final Runnable task) {
    spawn(replaying(() -> { delay(duration); spawn(task); }));
  }

  public static void defer(final Duration duration, final TaskFactory<?> task) {
    spawn(replaying(() -> { delay(duration); spawn(task); }));
  }

  public static void defer(final long quantity, final Duration unit, final Runnable task) {
    spawn(replaying(() -> { delay(quantity, unit); spawn(task); }));
  }

  public static void defer(final long quantity, final Duration unit, final TaskFactory<?> task) {
    spawn(replaying(() -> { delay(quantity, unit); spawn(task); }));
  }

  public static void deferWithSpan(final Duration duration, final Runnable task) {
    spawn(replaying(() -> { delay(duration); spawnWithSpan(task); }));
  }

  public static void deferWithSpan(final Duration duration, final TaskFactory<?> task) {
    spawn(replaying(() -> { delay(duration); spawnWithSpan(task); }));
  }

  public static void deferWithSpan(final long quantity, final Duration unit, final Runnable task) {
    spawn(replaying(() -> { delay(quantity, unit); spawnWithSpan(task); }));
  }

  public static void deferWithSpan(final long quantity, final Duration unit, final TaskFactory<?> task) {
    spawn(replaying(() -> { delay(quantity, unit); spawnWithSpan(task); }));
  }


  public static void delay(final Duration duration) {
    context.get().delay(duration);
  }

  public static void delay(final long quantity, final Duration unit) {
    delay(unit.times(quantity));
  }

  public static void waitUntil(final Condition condition) {
    context.get().waitUntil(condition);
  }

  public static <T> void startActivity(T activity, Topic<T> inputTopic) {
    context.get().startActivity(activity, inputTopic);
  }

  public static <T> void endActivity(T result, Topic<T> outputTopic) {
    context.get().endActivity(result, outputTopic);
  }
}
