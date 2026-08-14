package gov.nasa.jpl.aerie.merlin.framework;

import java.util.function.Supplier;

/**
 * A dynamically-scoped cell.
 *
 * Values pushed into the cell are strictly popped in the same order,
 * and the lifetime of each value is tied to the call stack.
 *
 * See the related concept of
 * <a href="https://cr.openjdk.java.net/~rpressler/loom/loom/sol1_part2.html#scope-variables">scoped variables
 * in Project Loom</a>.
 */
public final class Scoped<T> implements Supplier<T> {
    private final ThreadLocal<T> dynamicSlot;

    private Scoped(final ThreadLocal<T> dynamicSlot) {
        this.dynamicSlot = dynamicSlot;
    }

  /**
   * Create a dynamically-scoped cell containing values of type {@link T}.
   *
   * @param <T> The type of value this cell will contain.
   * @return A new cell that can contain values of type {@link T}.
   */
    public static <T> Scoped<T> create() {
        return new Scoped<>(ThreadLocal.withInitial(() -> null));
    }

    /**
     * Gets the current value of the dynamic cell.
     *
     * Throws an exception if nobody up-stack is currently serving this cell.
     */
    @Override
    public T get() throws EmptyDynamicCellException {
        final var value = dynamicSlot.get();
        if (value == null) throw new EmptyDynamicCellException();
        return value;
    }

    /**
     * Set a value in this cell, returning an {@link AutoCloseable} resource restoring the previous value on close.
     *
     * <p>
     *   This method should always be used in a try-with-resources statement:
     * </p>
     *
     * <pre>
     * try (final var scope = cell.set(value)) {
     *   // ...
     * }
     * </pre>
     */
    public UndoToken<T> set(final T newValue) {
        final var oldValue = this.dynamicSlot.get();
        this.dynamicSlot.set(newValue);
        return new UndoToken<>(this, oldValue);
    }

    public record UndoToken<T>(Scoped<T> cell, T oldValue) implements AutoCloseable {
        @Override
        public void close() {
            this.cell.dynamicSlot.set(this.oldValue);
        }
    }

    public static class EmptyDynamicCellException extends RuntimeException {}
}
