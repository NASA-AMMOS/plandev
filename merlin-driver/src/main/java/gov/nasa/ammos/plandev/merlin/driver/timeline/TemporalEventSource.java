package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.driver.engine.SlabList;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.Iterator;
import java.util.Set;

public record TemporalEventSource(SlabList<TimePoint> points) implements EventSource, Iterable<TemporalEventSource.TimePoint> {
  public TemporalEventSource() {
    this(new SlabList<>());
  }

  public void add(final Duration delta) {
    if (delta.isZero()) return;
    this.points.append(new TimePoint.Delta(delta));
  }

  public void add(final EventGraph<Event> graph) {
    if (graph instanceof EventGraph.Empty) return;
    this.points.append(new TimePoint.Commit(graph, extractTopics(graph)));
  }

  @Override
  public Iterator<TimePoint> iterator() {
    return TemporalEventSource.this.points.iterator();
  }

  @Override
  public TemporalCursor cursor() {
    return new TemporalCursor();
  }

  public final class TemporalCursor implements Cursor {
    private final SlabList<TimePoint>.SlabIterator iterator = TemporalEventSource.this.points.iterator();

    private TemporalCursor() {}

    @Override
    public void stepUp(final Cell<?> cell) {
      while (this.iterator.hasNext()) {
        final var point = this.iterator.next();

        if (point instanceof TimePoint.Delta p) {
          cell.step(p.delta());
        } else if (point instanceof TimePoint.Commit p) {
          if (cell.isInterestedIn(p.topics())) cell.apply(p.events());
        } else {
          throw new IllegalStateException();
        }
      }
    }
  }


  private static Set<Topic<?>> extractTopics(final EventGraph<Event> graph) {
    final var set = new ReferenceOpenHashSet<Topic<?>>();
    extractTopics(set, graph);
    set.trim();
    return set;
  }

  private static void extractTopics(final Set<Topic<?>> accumulator, EventGraph<Event> graph) {
    while (true) {
      if (graph instanceof EventGraph.Empty) {
        // There are no events here!
        return;
      } else if (graph instanceof EventGraph.Atom<Event> g) {
        accumulator.add(g.atom().topic());
        return;
      } else if (graph instanceof EventGraph.Sequentially<Event> g) {
        extractTopics(accumulator, g.prefix());
        graph = g.suffix();
      } else if (graph instanceof EventGraph.Concurrently<Event> g) {
        extractTopics(accumulator, g.left());
        graph = g.right();
      } else {
        throw new IllegalArgumentException();
      }
    }
  }

  public sealed interface TimePoint {
    record Delta(Duration delta) implements TimePoint {}
    record Commit(EventGraph<Event> events, Set<Topic<?>> topics) implements TimePoint {}
  }

  public void freeze() {
    this.points.freeze();
  }
}
