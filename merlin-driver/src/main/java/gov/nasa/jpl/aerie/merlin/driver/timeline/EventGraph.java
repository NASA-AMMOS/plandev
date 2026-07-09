package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * An immutable tree-representation of a graph of sequentially- and concurrently-composed events.
 *
 * <p>
 * An event graph is a <a href="https://en.wikipedia.org/wiki/Series-parallel_graph">series-parallel graph</a>
 * whose edges represent atomic events. Event graphs may be composed sequentially (in series) or concurrently (in
 * parallel).
 * </p>
 *
 * <p>
 * As with many recursive tree-like structures, an event graph is utilized by accepting an {@link EffectTrait} visitor
 * and traversing the series-parallel structure recursively. This trait provides methods for each type of node in the
 * tree representation (empty, sequential composition, and parallel composition). For each node, the trait combines
 * the results from its children into a result that will be provided to the same trait at the node's parent. The result
 * of the traversal is the value computed by the trait at the root node.
 * </p>
 *
 * <p>
 * Different domains may interpret each event differently, and so evaluate the same event graph under different
 * projections. An event may have no particular effect in one domain, while being critically important to another
 * domain.
 * </p>
 *
 * @param <Event> The type of event to be stored in the graph structure.
 * @see EffectTrait
 */
public sealed interface EventGraph<Event> extends EffectExpression<Event> {
  /**
   * Compare two events based on their ordering.
   * @param e1 an event
   * @param e2 an event
   * @return an integer less than 0 if e1 is sequentially before e2,
   *         an integer greater than 0 if the e1 is sequentially after e2,
   *         else 0.
   */
  //int compare(Event e1, Event e2);
  /** Use {@link EventGraph#empty()} instead of instantiating this class directly. */

  record Empty<Event>() implements EventGraph<Event> {
    // The behavior of the empty graph is independent of the parameterized Event type,
    // so we cache a single instance and re-use it for all Event types.
    private static final EventGraph<?> EMPTY = new Empty<>();

    @Override
    public String toString() {
      return EffectExpressionDisplay.displayGraph(this);
      //return "EventGraph(" + hashCode() + ", " + EffectExpressionDisplay.displayGraph(this) + ")";
    }
    @Override
    public boolean equals(Object o) {
      // Making this explicit because a structural equals() is problematic in data structures of these
      return this == o;
    }

    //@Override
    public int compare(final Event e1, final Event e2) {
      return 0;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }

  /** Use {@link EventGraph#atom} instead of instantiating this class directly. */
  record Atom<Event>(Event atom) implements EventGraph<Event> {
    @Override
    public String toString() {
      return EffectExpressionDisplay.displayGraph(this);
      //return "EventGraph(" + hashCode() + ", " + EffectExpressionDisplay.displayGraph(this) + ")";
    }
    @Override
    public boolean equals(Object o) {
      return this == o;
    }

    //@Override
    public int compare(final Event e1, final Event e2) {
      return 0;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }

  /** Use {@link EventGraph#sequentially(EventGraph[])}} instead of instantiating this class directly. */
  record Sequentially<Event>(EventGraph<Event> prefix, EventGraph<Event> suffix) implements EventGraph<Event> {
    @Override
    public String toString() {
      return EffectExpressionDisplay.displayGraph(this);
      //return "EventGraph(" + hashCode() + ", " + EffectExpressionDisplay.displayGraph(this) + ")";
    }
    @Override
    public boolean equals(Object o) {
      return this == o;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }

  /** Use {@link EventGraph#concurrently(EventGraph[])}} instead of instantiating this class directly. */
  record Concurrently<Event>(EventGraph<Event> left, EventGraph<Event> right) implements EventGraph<Event> {
    @Override
    public String toString() {
      return EffectExpressionDisplay.displayGraph(this);
      //return "EventGraph(" + hashCode() + ", " + EffectExpressionDisplay.displayGraph(this) + ")";
    }
    @Override
    public boolean equals(Object o) {
      return this == o;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }
  }


  /**
   * This is a non-recursive alternative to {@link EventGraph#evaluate(EffectTrait, Function)}.
   * <p/>
   * Initial testing shows no speed improvement over the recursive version because the call to
   * {@code substitution.apply()} was relatively expensive, and the overhead of recursion seems
   * to be less than the overhead of {@link HashMap}s used here.
   * <p/>
   * Another approach could use a stack of {code EventGraph}s to mimic the call stack of the recursive method.
   * Intermediate results would still need to stored, but this could have the advantage of avoiding the overhead of
   * {@link HashMap} puts and gets.
   * <p/>
   * It may be worth using a non-recursive version just to avoid potential stack overflow for large graphs.
   */
  default <Effect> Effect evaluateNonRecursively(final EffectTrait<Effect> trait, final Function<Event, Effect> substitution) {
    HashMap<EventGraph<Event>, EventGraph<Event>> parents = new HashMap<>();
    HashMap<EventGraph<Event>, Effect> results = new HashMap<>();
    EventGraph<Event> g = this;
    Effect r = null;
    while (true) {
      if (g == null) break;
      // TODO -- use switch on type like this for expected efficiency improvement
      //      switch (g) {
      //        case EventGraph.Empty ee:
      //          r = trait.empty();
      //          break;
      //        case EventGraph.Atom<Event> gg:
      //          r = substitution.apply(gg.atom());
      //          break;
      //      }
      if (g instanceof EventGraph.Empty) {
        r = trait.empty();
      } else if (g instanceof EventGraph.Atom<Event> gg) {
        r = substitution.apply(gg.atom());
      } else if (g instanceof EventGraph.Sequentially<Event> gg) {
        var r1 = results.get(gg.prefix());
        if (r1 == null) {
          parents.put(gg.prefix(), gg);
          g = gg.prefix();
          continue;
        }
        var r2 = results.get(gg.suffix());
        if (r2 == null) {
          parents.put(gg.suffix(), gg);
          g = gg.suffix();
          continue;
        }
        r = trait.sequentially(r1, r2);
      } else if (g instanceof EventGraph.Concurrently<Event> gg) {
        var r1 = results.get(gg.left());
        if (r1 == null) {
          parents.put(gg.left(), gg);
          g = gg.left();
          continue;
        }
        var r2 = results.get(gg.right());
        if (r2 == null) {
          parents.put(gg.right(), gg);
          g = gg.right();
          continue;
        }
        r = trait.concurrently(r1, r2);
      } else {
        throw new IllegalArgumentException();
      }
      results.put(g, r);
      g = parents.get(g);
    }
    return results.get(this);
  }

  default <Effect> Effect evaluate(final EffectTrait<Effect> trait, final Function<Event, Effect> substitution) {
    if (this instanceof EventGraph.Empty) {
      return trait.empty();
    } else if (this instanceof EventGraph.Atom<Event> g) {
      return substitution.apply(g.atom());
    } else if (this instanceof EventGraph.Sequentially<Event> g) {
      return trait.sequentially(
          g.prefix().evaluate(trait, substitution),
          g.suffix().evaluate(trait, substitution));
    } else if (this instanceof EventGraph.Concurrently<Event> g) {
      return trait.concurrently(
          g.left().evaluate(trait, substitution),
          g.right().evaluate(trait, substitution));
    } else {
      throw new IllegalArgumentException();
    }
  }

  default long count() {
    if (this instanceof EventGraph.Empty) {
      return 1;
    } else if (this instanceof EventGraph.Atom<Event> g) {
      return 1;
    } else if (this instanceof EventGraph.Sequentially<Event> g) {
      return g.prefix.count() + g.suffix.count();
    } else if (this instanceof EventGraph.Concurrently<Event> g) {
      return g.left.count() + g.right.count();
    } else {
      throw new IllegalArgumentException();
    }
  }

  default long countNonEmpty() {
    if (this instanceof EventGraph.Empty) {
      return 0;
    } else if (this instanceof EventGraph.Atom<Event> g) {
      return 1;
    } else if (this instanceof EventGraph.Sequentially<Event> g) {
      return g.prefix.countNonEmpty() + g.suffix.countNonEmpty();
    } else if (this instanceof EventGraph.Concurrently<Event> g) {
      return g.left.countNonEmpty() + g.right.countNonEmpty();
    } else {
      throw new IllegalArgumentException();
    }
  }


  /**
   * Return a subset of the graph filtering on events.
   * @param f a boolean Function testing whether an Event should remain in the graph
   * @return an empty graph if no events remain, {@code this} graph if no events are removed, or else a new graph with filtered events.
   */
  default EventGraph<Event> filter(Function<Event, Boolean> f) {
    // Instead of redefining filter() and evaluate() in each class, they are implemented for each Class here in one function.
    // This is so it's easier to follow the logic with it all in one place.  For this very situation Java 17 has a preview feature
    // for Pattern Matching for switch.
    // Would it be better to create a class implementing EffectTrait<EventGraph> and just call evaluate?
    // --> No, it would always make a copy of the graph, and we want to preserve it in some cases.

    if (this instanceof EventGraph.Empty) return this;
    if (this instanceof EventGraph.Atom<Event> g) {
      if (f.apply(g.atom)) return g;
      return EventGraph.empty();
    }
    if (this instanceof EventGraph.Sequentially<Event> g) {
      var g1 = g.prefix.filter(f);
      var g2 = g.suffix.filter(f);
      if (g.prefix == g1 && g.suffix == g2) return this;
      if (g1 instanceof EventGraph.Empty<Event>) return g2;
      if (g2 instanceof EventGraph.Empty<Event>) return g1;
      return sequentially(g1, g2);
    }
    if (this instanceof EventGraph.Concurrently<Event> g) {
      var g1 = g.left.filter(f);
      var g2 = g.right.filter(f);
      if (g.left == g1 && g.right == g2) return this;
      if (g1 instanceof EventGraph.Empty<Event>) return g2;
      if (g2 instanceof EventGraph.Empty<Event>) return g1;
      return concurrently(g1, g2);
    } else {
      throw new IllegalArgumentException();
    }
  }

  /**
   * Return a subset of the graph filtering on events using a Boolean function.  If {@code afterEvent} is not null,
   * then all events before and including {@code afterEvent} are included or excluded in the resulting graph according
   * to a flag, {@code includeBefore}.
   *
   * @param f a boolean Function testing whether an Event should remain in the graph
   * @param afterEvent the event after which the filter test is to be applied
   * @param includeBefore whether to include, else exclude, events prior to and including {@code afterEvent}
   * @return a filtered event graph paired with a Boolean indicating whether {@code afterEvent} was encountered;
   *         the returned graph is an empty graph if no events remain, {@code this} graph if no events are removed,
   *         or else a new graph with filtered events.
   */
  default Pair<EventGraph<Event>, Boolean> filter(Function<Event, Boolean> f, Event afterEvent, boolean includeBefore) {
    // Instead of redefining filter() and evaluate() in each class, they are implemented for each Class here in one function.
    // This is so it's easier to follow the logic with it all in one place.  For this very situation Java 17 has a preview feature
    // for Pattern Matching for switch.
    // Would it be better to create a class implementing EffectTrait<EventGraph> and just call evaluate?
    // --> No, it would always make a copy of the graph, and we want to preserve it in some cases.

    if (this instanceof EventGraph.Empty) return Pair.of(this, false);
    if (this instanceof EventGraph.Atom<Event> g) {
      // afterEvent == null && f(g) ||
      // afterEvent != null && includeBefore
      if ((afterEvent != null && includeBefore) || (afterEvent == null && f.apply(g.atom))) return Pair.of(g, afterEvent != null && g.atom == afterEvent);
      return Pair.of(EventGraph.empty(), afterEvent != null && g.atom == afterEvent);
    }
    if (this instanceof EventGraph.Sequentially<Event> g) {
      var p1 = g.prefix.filter(f, afterEvent, includeBefore);
      var g1 = p1.getLeft();
      var foundEvent = p1.getRight();
      var p2 = g.suffix.filter(f, foundEvent ? null : afterEvent, includeBefore);
      var g2 = p2.getLeft();
      foundEvent = foundEvent || p2.getRight();
      if (g.prefix == g1 && g.suffix == g2) return Pair.of(this, foundEvent);
      if (g1 instanceof EventGraph.Empty<Event>) return Pair.of(g2, foundEvent);
      if (g2 instanceof EventGraph.Empty<Event>) return Pair.of(g1, foundEvent);
      return Pair.of(sequentially(g1, g2), foundEvent);
    }
    if (this instanceof EventGraph.Concurrently<Event> g) {
      var p1 = g.left.filter(f, afterEvent, includeBefore);
      var g1 = p1.getLeft();
      var p2 = g.right.filter(f, afterEvent, includeBefore);
      var g2 = p2.getLeft();
      var foundEvent = p1.getRight() || p2.getRight();
      if (g.left == g1 && g.right == g2) return Pair.of(this, foundEvent);
      if (g1 instanceof EventGraph.Empty<Event>) return Pair.of(g2, foundEvent);
      if (g2 instanceof EventGraph.Empty<Event>) return Pair.of(g1, foundEvent);
      return Pair.of(concurrently(g1, g2), foundEvent);
    } else {
      throw new IllegalArgumentException();
    }
  }


  /**
   * Remove all occurrences of an Event from the graph, returning {@code this} EventGraph if and
   * only if there are no removals, else a new graph.
   * @param e the Event to remove
   * @return a new graph if there are any changes, else {@code this}
   */
  default EventGraph<Event> remove(Event e) {
    return filter(ev -> !ev.equals(e));
  }

  /**
   * Create an empty event graph.
   *
   * @param <Event> The type of event that might be contained by this event graph.
   * @return An empty event graph.
   */
  @SuppressWarnings("unchecked")
  static <Event> EventGraph<Event> empty() {
    return (EventGraph<Event>) Empty.EMPTY;
  }

  /**
   * Create an event graph consisting of a single atomic event.
   *
   * @param atom An atomic event.
   * @param <Event> The type of the given atomic event.
   * @return An event graph consisting of a single atomic event.
   */
  static <Event> EventGraph<Event> atom(final Event atom) {
    return new Atom<>(Objects.requireNonNull(atom));
  }

  /**
   * Create an event graph by combining multiple event graphs of the same type in sequence.
   *
   * @param prefix The first event graph to apply.
   * @param suffix The second event graph to apply.
   * @param <Event> The type of atomic event contained by these graphs.
   * @return An event graph consisting of a sequence of subgraphs.
   */
  static <Event> EventGraph<Event> sequentially(final EventGraph<Event> prefix, final EventGraph<Event> suffix) {
    if (prefix instanceof Empty) return suffix;
    if (suffix instanceof Empty) return prefix;

    return new Sequentially<>(Objects.requireNonNull(prefix), Objects.requireNonNull(suffix));
  }

  /**
   * Create an event graph by combining multiple event graphs of the same type in parallel.
   *
   * @param left An event graph to apply concurrently.
   * @param right An event graph to apply concurrently.
   * @param <Event> The type of atomic event contained by these graphs.
   * @return An event graph consisting of a set of concurrent subgraphs.
   */
  static <Event> EventGraph<Event> concurrently(final EventGraph<Event> left, final EventGraph<Event> right) {
    if (left instanceof Empty) return right;
    if (right instanceof Empty) return left;

    return new Concurrently<>(Objects.requireNonNull(left), Objects.requireNonNull(right));
  }

  /**
   * Create an event graph by combining multiple event graphs of the same type in sequence.
   *
   * @param segments A series of event graphs to combine in sequence.
   * @param <Event> The type of atomic event contained by these graphs.
   * @return An event graph consisting of a sequence of subgraphs.
   */
  static <Event> EventGraph<Event> sequentially(final List<EventGraph<Event>> segments) {
    var acc = EventGraph.<Event>empty();
    for (final var segment : segments) acc = sequentially(acc, segment);
    return acc;
  }

  /**
   * Create an event graph by combining multiple event graphs of the same type in parallel.
   *
   * @param branches A set of event graphs to combine in parallel.
   * @param <Event> The type of atomic event contained by these graphs.
   * @return An event graph consisting of a set of concurrent subgraphs.
   */
  static <Event> EventGraph<Event> concurrently(final Collection<EventGraph<Event>> branches) {
    var acc = EventGraph.<Event>empty();
    for (final var branch : branches) acc = concurrently(acc, branch);
    return acc;
  }

  /**
   * Create an event graph by combining multiple event graphs of the same type in sequence.
   *
   * @param segments A series of event graphs to combine in sequence.
   * @param <Event> The type of atomic event contained by these graphs.
   * @return An event graph consisting of a sequence of subgraphs.
   */
  @SafeVarargs
  static <Event> EventGraph<Event> sequentially(final EventGraph<Event>... segments) {
    return sequentially(Arrays.asList(segments));
  }

  /**
   * Create an event graph by combining multiple event graphs of the same type in parallel.
   *
   * @param branches A set of event graphs to combine in parallel.
   * @param <Event> The type of atomic event contained by these graphs.
   * @return An event graph consisting of a set of concurrent subgraphs.
   */
  @SafeVarargs
  static <Event> EventGraph<Event> concurrently(final EventGraph<Event>... branches) {
    return concurrently(Arrays.asList(branches));
  }

  /** A "no-op" algebra that reconstructs an event graph from its pieces. */
  final class IdentityTrait<T> implements EffectTrait<EventGraph<T>> {
    @Override
    public EventGraph<T> empty() {
      return EventGraph.empty();
    }

    @Override
    public EventGraph<T> sequentially(final EventGraph<T> prefix, final EventGraph<T> suffix) {
      return EventGraph.sequentially(prefix, suffix);
    }

    @Override
    public EventGraph<T> concurrently(final EventGraph<T> left, final EventGraph<T> right) {
      return EventGraph.concurrently(left, right);
    }
  }
}
