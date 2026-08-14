package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Objects;
import java.util.function.Function;

/**
 * Declares the ability of an object to be evaluated under an {@link EffectTrait}.
 *
 * <p>
 * Effect expressions describe a series-parallel graph of abstract effects called "events". The {@link EventGraph} class
 * is a concrete realization of this idea. However, if the expression is immediately consumed after construction,
 * the <code>EventGraph</code> imposes construction of needless intermediate data. Producers of effects will
 * typically want to return a custom implementor of this class that will directly produce the desired expression
 * for a given {@link EffectTrait}.
 * </p>
 *
 * @param <Event> The type of abstract effect in this expression.
 * @see EventGraph
 * @see EffectTrait
 */
public interface EffectExpression<Event> {
  /**
   * Produce an effect in the domain of effects described by the provided trait and event substitution.
   *
   * @param trait A visitor to be used to compose effects in sequence or concurrently.
   * @param substitution A visitor to be applied at any atomic events.
   * @param <Effect> The type of effect produced by the visitor.
   * @return The effect described by this object, within the provided domain of effects.
   */
  <Effect> Effect evaluate(final EffectTrait<Effect> trait, final Function<Event, Effect> substitution);

  /**
   * Produce an effect in the domain of effects described by the provided {@link EffectTrait}.
   *
   * @param trait A visitor to be used to compose effects in sequence or concurrently.
   * @return The effect described by this object, within the provided domain of effects.
   */
  default Event evaluate(final EffectTrait<Event> trait) {
    return this.evaluate(trait, x -> x);
  }

  /**
   * Transform abstract effects without evaluating the expression.
   *
   * <p>
   * This is a functorial "map" operation.
   * </p>
   *
   * @param transformation A transformation to be applied to each event.
   * @param <TargetType> The type of abstract effect in the result expression.
   * @return An equivalent expression over a different set of events.
   */
  default <TargetType> EffectExpression<TargetType> map(final Function<Event, TargetType> transformation) {
    Objects.requireNonNull(transformation);

    // Although it would be _correct_ to return a whole new EventGraph with the events substituted, this is neither
    // necessary nor particularly efficient. Any two objects can be considered equivalent so long as every observation
    // that can be made of both of them is indistinguishable. (This concept is called "bisimulation".)
    //
    // Since the only way to "observe" an EventGraph is to evaluate it, we can simply return an object that evaluates in
    // the same way that a fully-reconstructed EventGraph would. This is easy to do: have the evaluate method perform
    // the given transformation before applying the substitution provided at evaluation time. No intermediate EventGraphs
    // need to be constructed.
    //
    // This is called the "Yoneda" transformation in the functional programming literature. We basically get it for free
    // when using visitors / object algebras in Java. See Edward Kmett's blog series on the topic
    // at http://comonad.com/reader/2011/free-monads-for-less/.
    final var that = this;
    return new EffectExpression<>() {
      @Override
      public <Effect> Effect evaluate(final EffectTrait<Effect> trait, final Function<TargetType, Effect> substitution) {
        return that.evaluate(trait, transformation.andThen(substitution));
      }

      @Override
      public String toString() {
        return EffectExpressionDisplay.displayGraph(this);
      }
    };
  }

  /**
   * Replace abstract effects with sub-expressions over other abstract effects.
   *
   * <p>
   * This is analogous to composing functions <code>f(x) = x + x</code> and <code>x(t) = 2*t</code>
   * to obtain <code>(f.g)(t) = 2*t + 2*t</code>. For example, for an expression <code>x; y</code>,
   * we may substitute <code>1 | 2</code> for <code>x</code> and <code>3</code> for <code>y</code>,
   * yielding <code>(1 | 2); 3</code>.
   * </p>
   *
   * <p>
   * This is a monadic "bind" operation.
   * </p>
   *
   * @param transformation A transformation from events to effect expressions.
   * @param <TargetType> The type of abstract effect in the result expression.
   * @return An equivalent expression over a different set of events.
   */
  default <TargetType> EffectExpression<TargetType> substitute(final Function<Event, EffectExpression<TargetType>> transformation) {
    Objects.requireNonNull(transformation);

    // As with `map`, we don't need to return a fully-reconstructed EventGraph. We can instead return an object that
    // evaluates in the same way that a fully-reconstructed EventGraph would, but with a more efficient representation.
    //
    // In this case, it is sufficient to return a single new object that, when visiting a leaf of the original event
    // graph, applies the provided substitution and then evaluates the resulting subtree, before then propagating that
    // result back up the original graph.
    //
    // This is called the "codensity" transformation in the functional programming literature. We basically get it for
    // free when using visitors / object algebras in Java. See Edward Kmett's blog series on the topic
    // at http://comonad.com/reader/2011/free-monads-for-less/.
    final var that = this;
    return new EffectExpression<>() {
      @Override
      public <Effect> Effect evaluate(final EffectTrait<Effect> trait, final Function<TargetType, Effect> substitution) {
        return that.evaluate(trait, v -> transformation.apply(v).evaluate(trait, substitution));
      }

      @Override
      public String toString() {
        return EffectExpressionDisplay.displayGraph(this);
      }
    };
  }
}
