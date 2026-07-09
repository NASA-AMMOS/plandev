package gov.nasa.jpl.aerie.merlin.server.remotes.postgres;

import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import static gov.nasa.jpl.aerie.merlin.driver.timeline.EffectExpressionDisplay.displayGraph;
import static gov.nasa.jpl.aerie.merlin.driver.EventGraphFlattener.flatten;
import static gov.nasa.jpl.aerie.merlin.server.remotes.postgres.EventGraphUnflattener.unflatten;
import static org.junit.jupiter.api.Assertions.assertEquals;

public final class EventGraphFlattenerTest {
  @Test
  void testFlattenLex() throws EventGraphUnflattener.InvalidTagException {
    final var eventGraph =
        EventGraph.concurrently(
            EventGraph.atom("a"),
            EventGraph.sequentially(
                EventGraph.atom("x"),
                EventGraph.sequentially(
                    EventGraph.concurrently(
                        EventGraph.atom("y"),
                        EventGraph.atom("z")),
                    EventGraph.atom("w"))));

    final var flattenedLex = flatten(eventGraph);
    final var unFlattenedLex = unflatten(flattenedLex);

    assertEquals("a | (x; (y | z); w)", eventGraph.toString());
    assertEquals(5, flattenedLex.size());
    assertEquals("a | (x; (y | z); w)", unFlattenedLex.toString());
  }

  @Property
  @Label("unflatten is a left inverse of flatten")
  public void flattenThenUnflatten(@ForAll("fanout") final EventGraph<String> graph)
  throws EventGraphUnflattener.InvalidTagException
  {
    final var result = unflatten(flatten(graph));

    // Equivalent graphs have equal string representations.
    assertEquals(displayGraph(graph), displayGraph(result));
  }

  // Generates arbitrary graphs with the "fanout" property: no event has a Concurrently node in its past.
  // TaskFrame can't generate graphs with the subgraph `(x | y); z`; events cannot be emitted
  // with two branches in their history. We exclude such graphs from generation.
  @Provide("fanout")
  public static Arbitrary<EventGraph<String>> fanoutGraphs() {
    return eventGraphs(Arbitraries.strings())
        .filter(EventGraphFlattenerTest::isFanoutGraph);
  }

  private static <T> Arbitrary<EventGraph<T>> eventGraphs(final Arbitrary<T> atoms) {
    return Arbitraries
        .lazyOf(
            () -> Arbitraries.just(EventGraph.empty()),
            () -> atoms.map(EventGraph::atom),
            () -> eventGraphs(atoms).tuple2().map($ -> EventGraph.concurrently($.get1(), $.get2())),
            () -> eventGraphs(atoms).tuple2().map($ -> EventGraph.sequentially($.get1(), $.get2())));
  }

  private static <T> boolean isFanoutGraph(final EventGraph<T> graph) {
    if (graph instanceof EventGraph.Empty) {
      return true;
    } else if (graph instanceof EventGraph.Atom) {
      return true;
    } else if (graph instanceof EventGraph.Concurrently<T> g) {
      return isFanoutGraph(g.left()) && isFanoutGraph(g.right());
    } else if (graph instanceof EventGraph.Sequentially<T> g) {
      return !hasConcurrentNode(g.prefix()) && isFanoutGraph(g.suffix());
    } else {
      throw new IllegalArgumentException();
    }
  }

  private static <T> boolean hasConcurrentNode(final EventGraph<T> graph) {
    if (graph instanceof EventGraph.Empty) {
      return false;
    } else if (graph instanceof EventGraph.Atom) {
      return false;
    } else if (graph instanceof EventGraph.Concurrently<T>) {
      return true;
    } else if (graph instanceof EventGraph.Sequentially<T> g) {
      return hasConcurrentNode(g.prefix()) || hasConcurrentNode(g.suffix());
    } else {
      throw new IllegalArgumentException();
    }
  }
}
