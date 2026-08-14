package gov.nasa.jpl.aerie.contrib.streamline.debugging;

import gov.nasa.jpl.aerie.contrib.streamline.core.MutableResource;
import gov.nasa.jpl.aerie.contrib.streamline.core.Dynamics;
import gov.nasa.jpl.aerie.contrib.streamline.core.DynamicsEffect;
import gov.nasa.jpl.aerie.contrib.streamline.core.ErrorCatching;
import gov.nasa.jpl.aerie.contrib.streamline.core.Expiring;
import gov.nasa.jpl.aerie.contrib.streamline.core.Resource;
import gov.nasa.jpl.aerie.merlin.framework.Condition;
import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;

import java.util.Stack;
import java.util.function.Supplier;

import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentTime;

/**
 * Functions for debugging resources by tracing their calculation.
 */
public final class Tracing {
  private Tracing() {}

  private final static Stack<String> activeTracePoints = new Stack<>();

  public static <D> Resource<D> trace(Resource<D> resource) {
    return trace(() -> Naming.getName(resource).orElse("anonymous resource"), resource);
  }

  public static <D> Resource<D> trace(String name, Resource<D> resource) {
    return trace(() -> name, resource);
  }

  public static <D> Resource<D> trace(Supplier<String> name, Resource<D> resource) {
    return () -> traceAction(name, resource::getDynamics);
  }

  public static <D extends Dynamics<?, D>> MutableResource<D> trace(MutableResource<D> resource) {
    return trace(() -> Naming.getName(resource).orElse("anonymous resource"), resource);
  }

  public static <D extends Dynamics<?, D>> MutableResource<D> trace(String name, MutableResource<D> resource) {
    return trace(() -> name, resource);
  }

  public static <D extends Dynamics<?, D>> MutableResource<D> trace(Supplier<String> name, MutableResource<D> resource) {
    return new MutableResource<>() {
      private final Resource<D> tracedResource = trace(name, (Resource<D>) resource);

      @Override
      public void emit(final DynamicsEffect<D> effect) {
        traceAction(
                () -> String.format("Emit '%s' on %s",
                        Naming.getName(effect).orElse("anonymous effect"),
                        name.get()),
                () -> { resource.emit(effect); return Unit.UNIT; });
      }

      @Override
      public ErrorCatching<Expiring<D>> getDynamics() {
        return tracedResource.getDynamics();
      }
    };
  }

  public static Condition trace(Condition condition) {
    return trace(() -> Naming.getName(condition).orElse("anonymous condition"), condition);
  }

  public static Condition trace(String name, Condition condition) {
    return trace(() -> name, condition);
  }

  public static Condition trace(Supplier<String> name, Condition condition) {
    return (positive, atEarliest, atLatest) ->
        traceAction(() -> name.get() + " evaluate (%s, %s, %s)".formatted(positive, atEarliest, atLatest), () -> condition.nextSatisfied(positive, atEarliest, atLatest));
  }

  public static Supplier<Condition> trace(Supplier<Condition> condition) {
    return trace(() -> Naming.getName(condition).orElse("anonymous condition"), condition);
  }

  public static Supplier<Condition> trace(String name, Supplier<Condition> condition) {
    return trace(() -> name, condition);
  }

  public static Supplier<Condition> trace(Supplier<String> name, Supplier<Condition> condition) {
    // Trace calling the supplier separately from tracing the condition itself.
    return () -> traceAction(() -> name.get() + " (generation)", () -> trace(name, condition.get()));
  }

  private static <T> T traceAction(Supplier<String> name, Supplier<T> action) {
    activeTracePoints.push(name.get());
    System.out.printf("TRACE: %s - %s start...%n", currentTime(), formatStack());
    T result = action.get();
    System.out.printf("TRACE: %s - %s: %s%n", currentTime(), formatStack(), result);
    activeTracePoints.pop();
    return result;
  }

  private static String formatStack() {
    return String.join("->", activeTracePoints);
  }
}
