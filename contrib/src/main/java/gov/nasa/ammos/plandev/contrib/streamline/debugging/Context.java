package gov.nasa.jpl.aerie.contrib.streamline.debugging;

import gov.nasa.jpl.aerie.merlin.protocol.types.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Unit.UNIT;

/**
 * Thread-local scope-bound description of the current context.
 */
public final class Context {
  private Context() {}

  private static final ThreadLocal<List<String>> contexts = ThreadLocal.withInitial(List::of);

  /**
   * @see Context#inContext(String, Supplier)
   */
  public static void inContext(String contextName, Runnable action) {
    inContext(contextName, asSupplier(action));
  }

  /**
   * Run action in a globally-visible context.
   * Contexts stack, and contexts are removed when control leaves action for any reason.
   */
  public static <R> R inContext(String contextName, Supplier<R> action) {
    // Using a thread-local context stack maintains isolation for threaded tasks.
    var outerContext = contexts.get();
    try {
      // Since copying the context into spawned children actually dominates performance,
      // use an effectively-immutable list for the stack, and copy it to push/pop a layer.
      var innerContext = new ArrayList<String>(outerContext.size() + 1);
      innerContext.addAll(outerContext);
      innerContext.add(contextName);
      contexts.set(innerContext);
      return action.get();
      // TODO: Should we add a catch clause here that would add context to the error?
    } finally {
      // Doing the tear-down in a finally block maintains isolation for replaying tasks.
      contexts.set(outerContext);
    }
  }

  /**
   * @see Context#inContext(List, Supplier)
   */
  public static void inContext(List<String> contextStack, Runnable action) {
    inContext(contextStack, asSupplier(action));
  }

  /**
   * Run action in a context stack like that returned by {@link Context#get}.
   *
   * <p>
   *   This can be used to "copy" a context into another task, e.g.
   *   <pre>
   *     var context = Context.get();
   *     spawn(() -> inContext(context, () -> { ... });
   *   </pre>
   * </p>
   *
   * @see Context#contextualized
   */
  public static <R> R inContext(List<String> contextStack, Supplier<R> action) {
    var outerContext = contexts.get();
    try {
      contexts.set(contextStack);
      return action.get();
    } finally {
      contexts.set(outerContext);
    }
  }

  /**
   * @see Context#contextualized(Supplier)
   */
  public static Runnable contextualized(Runnable action) {
    return contextualized(asSupplier(action))::get;
  }

  /**
   * Adds the current context into action.
   *
   * <p>
   *   This can be used to contextualize sub-tasks with their parents context:
   *   <pre>
   *     inContext("parent", () -> {
   *       // Capture parent context while calling spawn:
   *       spawn(contextualized(() -> {
   *         // Runs child task in context "parent"
   *       }));
   *     });
   *   </pre>
   * </p>
   *
   * @see Context#contextualized(String, Runnable)
   * @see Context#inContext(List, Runnable)
   * @see Context#inContext(String, Runnable)
   */
  public static <R> Supplier<R> contextualized(Supplier<R> action) {
    final var context = get();
    return () -> inContext(context, action);
  }

  /**
   * @see Context#contextualized(String, Supplier)
   */
  public static Runnable contextualized(String childContext, Runnable action) {
    return contextualized(childContext, asSupplier(action))::get;
  }

  /**
   * Adds the current context into action, as well as an additional child context.
   *
   * <p>
   *   This can be used to contextualize sub-tasks with their parents context:
   *   <pre>
   *     inContext("parent", () -> {
   *       // Capture parent context while calling spawn:
   *       spawn(contextualized("child", () -> {
   *         // Runs child task in context ("child", "parent")
   *       }));
   *     });
   *   </pre>
   * </p>
   *
   * @see Context#contextualized(Runnable)
   * @see Context#inContext(List, Runnable)
   * @see Context#inContext(String, Runnable)
   */
  public static <R> Supplier<R> contextualized(String childContext, Supplier<R> action) {
    return contextualized(() -> inContext(childContext, action));
  }

  /**
   * Returns the list of contexts, from innermost context out.
   *
   * <p>
   *     Note: For efficiency, this returns the actual context stack, not a copy.
   *     Do not modify the result of this method!
   *     Doing so may corrupt the context-tracking system.
   * </p>
   */
  public static List<String> get() {
    return contexts.get();
  }

  private static Supplier<Unit> asSupplier(Runnable action) {
    return () -> {
      action.run();
      return UNIT;
    };
  }
}
