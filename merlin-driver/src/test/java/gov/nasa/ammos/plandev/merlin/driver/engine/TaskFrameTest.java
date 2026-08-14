package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.driver.timeline.CausalEventSource;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Cell;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EffectExpressionDisplay;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Event;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.driver.timeline.LiveCells;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Query;
import gov.nasa.jpl.aerie.merlin.driver.timeline.RecursiveEventGraphEvaluator;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Selector;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.commons.lang3.mutable.MutableObject;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class TaskFrameTest {
  private static final SpanId ORIGIN = SpanId.generate();

  // This regression test identified a bug in the LiveCells-chain-avoidance optimization in TaskFrame.
  @Test
  public void consecutiveSpawnsShareHistory() {
    final var graph =
        EventGraph.concurrently(
            EventGraph.sequentially(
                EventGraph.atom(1),
                EventGraph.concurrently(
                    EventGraph.concurrently(
                        EventGraph.atom(3),
                        EventGraph.atom(4)),
                    EventGraph.atom(2))),
            EventGraph.atom(0));

    taskHistoryIsCorrect(graph);
  }


	@Property
  @Label("TaskFrame should faithfully reassemble event graphs")
  public void producedGraphIsCorrect(@ForAll("fanout") EventGraph<Integer> graph) {
    final var events = new CausalEventSource();
    final var cells = new LiveCells(events);
    final var topic = new Topic<Integer>();

    final var result = TaskFrame
        .run(graph, cells, (job, builder) -> runGraph(topic, builder, job))
        .map($ -> EventGraph.atom($.extract(topic).orElseThrow()));

    // Equivalent graphs have equal string representations.
    assertEquals(
        EffectExpressionDisplay.displayGraph(graph),
        EffectExpressionDisplay.displayGraph(result));
  }

  private void runGraph(
      final Topic<Integer> topic,
      final TaskFrame<EventGraph<Integer>> frame,
      final EventGraph<Integer> graph
  ) {
    if (graph instanceof EventGraph.Empty) {
      return;
    } else if (graph instanceof EventGraph.Atom<Integer> g) {
      frame.emit(Event.create(topic, g.atom(), ORIGIN));
    } else if (graph instanceof EventGraph.Sequentially<Integer> g) {
      runGraph(topic, frame, g.prefix());
      runGraph(topic, frame, g.suffix());
    } else if (graph instanceof EventGraph.Concurrently<Integer> g) {
      frame.signal(g.right());
      runGraph(topic, frame, g.left());
    } else {
      throw new IllegalArgumentException();
    }
  }


	@Property
  @Label("TaskFrame should only make history available to tasks that should be able to observe it")
  public void taskHistoryIsCorrect(@ForAll("fanout") EventGraph<Integer> graph) {
    final var topic = new Topic<Integer>();
    final var query = new Query<MutableObject<EventGraph<Integer>>>();

    final var cellType = new MutableGraphCellType<Integer>();
    final var selector = new Selector<>(topic, EventGraph::atom);
    final var evaluator = new RecursiveEventGraphEvaluator();

    final var events = new CausalEventSource();
    final var cells = new LiveCells(events);
    cells.put(query, new Cell<>(cellType, selector, evaluator, new MutableObject<>(EventGraph.empty())));

    final var root = HistoryDecoratedGraph.decorate(graph);
    TaskFrame.run(root, cells, (job, builder) -> checkHistory(topic, query, builder, job));
  }

  private void checkHistory(
      final Topic<Integer> topic,
      final Query<MutableObject<EventGraph<Integer>>> query,
      final TaskFrame<EventGraph<Pair<EventGraph<Integer>, Integer>>> frame,
      final EventGraph<Pair<EventGraph<Integer>, Integer>> graph
  ) {
    if (graph instanceof EventGraph.Empty) {
      return;
    } else if (graph instanceof EventGraph.Atom<Pair<EventGraph<Integer>, Integer>> g) {
      assertEquals(g.atom().getLeft().toString(), frame.getState(query).orElseThrow().toString());
      frame.emit(Event.create(topic, g.atom().getRight(), ORIGIN));
    } else if (graph instanceof EventGraph.Sequentially<Pair<EventGraph<Integer>, Integer>> g) {
      checkHistory(topic, query, frame, g.prefix());
      checkHistory(topic, query, frame, g.suffix());
    } else if (graph instanceof EventGraph.Concurrently<Pair<EventGraph<Integer>, Integer>> g) {
      frame.signal(g.right());
      checkHistory(topic, query, frame, g.left());
    } else {
      throw new IllegalArgumentException();
    }
  }


  /** Generates arbitrary graphs with the "fanout" property: no event has a Concurrently node in its past. */
  // TaskFrame can't generate graphs with the subgraph `(x | y); z`; events cannot be emitted
  // with two branches in their history. We exclude such graphs from generation.
  @Provide("fanout")
  public static Arbitrary<EventGraph<Integer>> fanoutGraphs() {
    return eventGraphs(Arbitraries.integers()).filter(TaskFrameTest::isFanoutGraph);
  }

  private static <T> Arbitrary<EventGraph<T>> eventGraphs(Arbitrary<T> atoms) {
    return Arbitraries
        .lazyOf(
            () -> Arbitraries.just(EventGraph.empty()),
            () -> atoms.map(EventGraph::atom),
            () -> eventGraphs(atoms).tuple2().map($ -> EventGraph.concurrently($.get1(), $.get2())),
            () -> eventGraphs(atoms).tuple2().map($ -> EventGraph.sequentially($.get1(), $.get2())));
  }

  private static <T> boolean isFanoutGraph(final @ForAll("fanout") EventGraph<T> graph) {
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

  /** A graph where each event is decorated by the history of events in its past... up to a deferred choice of past.*/
  private sealed interface HistoryDecoratedGraph<T> {
    record Empty<T> ()
        implements HistoryDecoratedGraph<T> {}
    record Atom<T> (T atom)
        implements HistoryDecoratedGraph<T> {}
    record Sequentially<T> (HistoryDecoratedGraph<T> prefix, HistoryDecoratedGraph<T> suffix)
        implements HistoryDecoratedGraph<T> {}
    record Concurrently<T> (HistoryDecoratedGraph<T> left, HistoryDecoratedGraph<T> right)
        implements HistoryDecoratedGraph<T> {}

    /** Step a graph forward by the atoms contained within this decorated graph.*/
    default EventGraph<T> advance(final EventGraph<T> past) {
      if (this instanceof Empty) {
        return past;
      } else if (this instanceof Atom<T> f) {
        return EventGraph.sequentially(past, EventGraph.atom(f.atom()));
      } else if (this instanceof Sequentially<T> f) {
        return f.suffix().advance(f.prefix().advance(past));
      } else if (this instanceof Concurrently<T> f) {
        return EventGraph.concurrently(f.left().advance(EventGraph.empty()), f.right().advance(EventGraph.empty()));
      } else {
        throw new IllegalStateException();
      }
    }

    /** Choose the past against which this graph is relative. */
    default EventGraph<Pair<EventGraph<T>, T>> get(final EventGraph<T> past) {
      if (this instanceof Empty) {
        return EventGraph.empty();
      } else if (this instanceof Atom<T> f) {
        return EventGraph.atom(Pair.of(past, f.atom()));
      } else if (this instanceof Sequentially<T> f) {
        return EventGraph.sequentially(f.prefix().get(past), f.suffix().get(f.prefix().advance(past)));
      } else if (this instanceof Concurrently<T> f) {
        return EventGraph.concurrently(f.left().get(past), f.right().get(past));
      } else {
        throw new IllegalStateException();
      }
    }

    // Decorated graphs compose just like regular graphs.
    final class Trait<T> implements EffectTrait<HistoryDecoratedGraph<T>> {
      @Override
      public HistoryDecoratedGraph<T> empty() {
        return new HistoryDecoratedGraph.Empty<>();
      }

      @Override
      public HistoryDecoratedGraph<T>
      sequentially(final HistoryDecoratedGraph<T> prefix, final HistoryDecoratedGraph<T> suffix) {
        return new HistoryDecoratedGraph.Sequentially<>(prefix, suffix);
      }

      @Override
      public HistoryDecoratedGraph<T>
      concurrently(final HistoryDecoratedGraph<T> left, final HistoryDecoratedGraph<T> right) {
        return new HistoryDecoratedGraph.Concurrently<>(left, right);
      }
    }

    /** Decorate a given graph with the observed past at each event. */
    static <T> EventGraph<Pair<EventGraph<T>, T>> decorate(final EventGraph<T> graph) {
      return graph
          .evaluate(new HistoryDecoratedGraph.Trait<>(), HistoryDecoratedGraph.Atom::new)
          .get(EventGraph.empty());
    }
  }

  /** A cell applicator that sequentially appends graphs to an accumulator graph. */
  private static final class MutableGraphCellType<T> implements CellType<EventGraph<T>, MutableObject<EventGraph<T>>> {
    @Override
    public EffectTrait<EventGraph<T>> getEffectType() {
      return new EventGraph.IdentityTrait<T>();
    }

    @Override
    public MutableObject<EventGraph<T>> duplicate(final MutableObject<EventGraph<T>> self) {
      return new MutableObject<>(self.getValue());
    }

    @Override
    public void apply(final MutableObject<EventGraph<T>> self, final EventGraph<T> graph) {
      self.setValue(EventGraph.sequentially(self.getValue(), graph));
    }

    @Override
    public void step(final MutableObject<EventGraph<T>> self, final Duration delta) {
      // pass
    }

    @Override
    public Optional<Duration> getExpiry(final MutableObject<EventGraph<T>> self) {
      return Optional.empty();
    }
  }
}
