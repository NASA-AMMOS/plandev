package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;

import java.util.Objects;
import java.util.function.Function;

/**
 * A module for representing {@link EffectExpression}s in a textual form.
 *
 * <ul>
 *   <li>The empty expression is rendered as the empty string.</li>
 *   <li>A sequence of expressions is rendered as <code>(x; y)</code>.</li>
 *   <li>A concurrence of expressions is rendered as <code>(x | y)</code>.</li>
 * </ul>
 *
 * <p>
 * Because sequential and concurrent composition are associative (see {@link EffectTrait}), unnecessary parentheses
 * are elided.
 * </p>
 *
 * <p>
 * Because the empty effect is the identity for both kinds of composition, the empty expression is never rendered.
 * For instance, <code>sequentially(empty(), atom("x"))</code> will be rendered as <code>x</code>, as that graph
 * is observationally equivalent to <code>atom("x")</code>.
 * </p>
 *
 * @see EffectExpression
 * @see EffectTrait
 */
public final class EffectExpressionDisplay {
  private EffectExpressionDisplay() {}

  /**
   * Render an event graph as a string using the event type's natural {@link Object#toString} implementation.
   *
   * @param expression The event graph to render as a string.
   * @return A textual representation of the graph.
   */
  public static String displayGraph(final EffectExpression<?> expression) {
    return displayGraph(expression, Objects::toString);
  }

  /**
   * Render an event graph as a string using the given interpretation of events as strings.
   *
   * @param expression The event graph to render as a string.
   * @param stringifier An interpretation of atomic events as strings.
   * @param <Event> The type of event contained by the event graph.
   * @return A textual representation of the graph.
   */
  public static <Event> String displayGraph(final EffectExpression<Event> expression, final Function<Event, String> stringifier) {
    return expression
        .map(stringifier)
        .evaluate(new Display.Trait(), Display.Atom::new)
        .accept(Parent.Unrestricted);
  }

  private enum Parent { Unrestricted, Par, Seq }

  // An effect algebra for computing string representations of transactions.
  private sealed interface Display {
    String accept(Parent parent);

    record Atom(String value) implements Display {
      @Override
      public String accept(final Parent parent) {
        return this.value;
      }
    }

    record Empty() implements Display {
      @Override
      public String accept(final Parent parent) {
        return "";
      }
    }

    record Sequentially(Display prefix, Display suffix) implements Display {
      @Override
      public String accept(final Parent parent) {
        final var format = (parent == Parent.Par) ? "(%s; %s)" : "%s; %s";

        return format.formatted(this.prefix.accept(Parent.Seq), this.suffix.accept(Parent.Seq));
      }
    }

    record Concurrently(Display left, Display right) implements Display {
      @Override
      public String accept(final Parent parent) {
        final var format = (parent == Parent.Seq) ? "(%s | %s)" : "%s | %s";

        return format.formatted(this.left.accept(Parent.Par), this.right.accept(Parent.Par));
      }
    }

    record Trait() implements EffectTrait<Display> {
      @Override
      public Display empty() {
        return new Empty();
      }

      @Override
      public Display sequentially(final Display prefix, final Display suffix) {
        if (prefix instanceof Empty) return suffix;
        if (suffix instanceof Empty) return prefix;

        return new Sequentially(prefix, suffix);
      }

      @Override
      public Display concurrently(final Display left, final Display right) {
        if (left instanceof Empty) return right;
        if (right instanceof Empty) return left;

        return new Concurrently(left, right);
      }
    }
  }
}
