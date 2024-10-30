package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.utils.InvertibleFunction;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Combines the operations of getting initial state and saving final state,
 * since these operations are intentionally closely coupled.
 */
public interface InconBehavior<T> {
    T getIncon(InitialConditionManager.InitialConditions initialConditions);

    void writeFincon(T state, InitialConditionManager.FinalConditions finalConditions);

    static <T> InconBehavior<T> of(Function<InitialConditionManager.InitialConditions, T> getIncon, BiConsumer<T, InitialConditionManager.FinalConditions> writeFincon) {
        return new InconBehavior<T>() {
            @Override
            public T getIncon(InitialConditionManager.InitialConditions initialConditions) {
                return getIncon.apply(initialConditions);
            }

            @Override
            public void writeFincon(T state, InitialConditionManager.FinalConditions finalConditions) {
                writeFincon.accept(state, finalConditions);
            }
        };
    }

    /**
     * The null case of incon behavior, when in fact the state does not get saved out.
     */
    static <T> InconBehavior<T> constant(T value) {
        return InconBehavior.of($ -> value, (s, f) -> {});
    }

    /**
     * The standard case of incon behavior, where the state is serialized out under a single key.
     */
    static <T> InconBehavior<T> serialized(String key, Function<Optional<SerializedValue>, T> constructor, Function<T, SerializedValue> serializer) {
        return InconBehavior.of(
                incons -> constructor.apply(incons.get(key)),
                (state, fincons) -> fincons.put(key, serializer.apply(state)));
    }

    /**
     * Extend this {@link InconBehavior} with an invertible function.
     * <p>
     *     In particular, this can be used to apply {@link Resource} wrappers around the initial state.
     * </p>
     */
    default <U> InconBehavior<U> map(InvertibleFunction<T, U> f) {
        return InconBehavior.of(
                f.compose(this::getIncon),
                (state, fincons) -> this.writeFincon(f.inverse().apply(state), fincons));
    }
}
