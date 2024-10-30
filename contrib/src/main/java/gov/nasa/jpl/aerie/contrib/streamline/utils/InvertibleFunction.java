package gov.nasa.jpl.aerie.contrib.streamline.utils;

import java.util.function.Function;

public interface InvertibleFunction<A, B> extends Function<A, B> {
    InvertibleFunction<B, A> inverse();

    default <C> InvertibleFunction<C, B> compose(InvertibleFunction<C, A> before) {
        return new InvertibleFunction<>() {
            @Override
            public InvertibleFunction<B, C> inverse() {
                return before.inverse().<B>compose(InvertibleFunction.this.inverse());
            }

            @Override
            public B apply(C c) {
                return InvertibleFunction.this.apply(before.apply(c));
            }
        };
    }

    default <C> InvertibleFunction<A, C> andThen(InvertibleFunction<B, C> after) {
        return after.compose(this);
    }

    static <A, B> InvertibleFunction<A, B> of(Function<A, B> map, Function<B, A> inverse) {
        return new InvertibleFunction<>() {
            @Override
            public B apply(A a) {
                return map.apply(a);
            }

            @Override
            public InvertibleFunction<B, A> inverse() {
                return InvertibleFunction.of(inverse, map);
            }
        };
    }
}
