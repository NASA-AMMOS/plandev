package gov.nasa.jpl.aerie.contrib.streamline.core;

import gov.nasa.jpl.aerie.contrib.streamline.utils.InvertibleFunction;

import java.util.function.BiConsumer;
import java.util.function.Function;

import static gov.nasa.jpl.aerie.contrib.streamline.core.InitialConditionManager.*;

/**
 * Combines the operations of getting initial state and saving final state,
 * since these operations are intentionally closely coupled.
 */
public interface InconBehavior<T> {
    T getIncon(InitialConditions initialConditions);

    void writeFincon(T state, FinalConditions finalConditions);

    static <T> InconBehavior<T> of(Function<InitialConditions, T> getIncon, BiConsumer<T, FinalConditions> writeFincon) {
        return new InconBehavior<>() {
            @Override
            public T getIncon(InitialConditions initialConditions) {
                return getIncon.apply(initialConditions);
            }

            @Override
            public void writeFincon(T state, FinalConditions finalConditions) {
                writeFincon.accept(state, finalConditions);
            }
        };
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
