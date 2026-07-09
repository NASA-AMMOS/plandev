package gov.nasa.ammos.aerie.merlin.driver.test.framework;

import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.protocol.driver.CellId;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Initializer;
import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.model.CellType;
import gov.nasa.jpl.aerie.merlin.protocol.model.EffectTrait;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import org.apache.commons.lang3.mutable.MutableObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

public class History {
  final ArrayList<TimePoint> timeline = new ArrayList<>();

  public static History empty() {
    return new History();
  }

  public static History sequentially(History prefix, History suffix) {
    if (suffix.timeline.isEmpty()) return prefix;
    if (prefix.timeline.isEmpty()) return suffix;
    if (prefix.timeline.getLast() instanceof TimePoint.Delay p
        && suffix.timeline.getFirst() instanceof TimePoint.Delay s) {
      final var result = new History();
      result.timeline.addAll(prefix.timeline);
      result.timeline.removeLast();
      result.timeline.add(new TimePoint.Delay(p.duration.plus(s.duration)));
      for (int i = 1; i < suffix.timeline.size(); i++) { // Skip the first item
        final var it = suffix.timeline.get(i);
        result.timeline.add(it);
      }
      return result;
    } else if (prefix.timeline.getLast() instanceof TimePoint.Commit p
               && suffix.timeline.getFirst() instanceof TimePoint.Commit s) {
      final var result = new History();
      result.timeline.addAll(prefix.timeline);
      result.timeline.removeLast();
      result.timeline.add(new TimePoint.Commit(EventGraph.sequentially(p.graph, s.graph)));
      for (int i = 1; i < suffix.timeline.size(); i++) { // Skip the first item
        final var it = suffix.timeline.get(i);
        result.timeline.add(it);
      }
      return result;
    } else {
      final var result = new History();
      result.timeline.addAll(prefix.timeline);
      result.timeline.addAll(suffix.timeline);
      return result;
    }
  }

  public static History concurrently(History left, History right) {
    if (left.timeline.isEmpty()) return right;
    if (right.timeline.isEmpty()) return left;
    if (left.timeline.size() == 1 && right.timeline.size() == 1) {
      if (left.timeline.getFirst() instanceof TimePoint.Commit l
          && right.timeline.getFirst() instanceof TimePoint.Commit r) {
        final var res = new History();
        res.timeline.add(new TimePoint.Commit(rebalance((EventGraph.Concurrently<String>) EventGraph.concurrently(
            r.graph,
            l.graph))));
        return res;
      } else {
        throw new IllegalArgumentException("Cannot concurrently compose delays and commits: " + left + " | " + right);
      }
    } else {
      throw new IllegalArgumentException("Cannot concurrently compose non unit-length histories: "
                                         + left
                                         + " | "
                                         + right);
    }
  }

  static <T> EventGraph.Concurrently<T> rebalance(EventGraph.Concurrently<T> graph) {
    final List<EventGraph<T>> sorted = expandConcurrently(graph);
    sorted.sort(Comparator.comparing(EventGraph::toString));
    var res = EventGraph.<T>empty();
    for (final var item : sorted.reversed()) {
      res = EventGraph.concurrently(item, res);
    }
    return (EventGraph.Concurrently<T>) res;
  }

  static <T> List<EventGraph<T>> expandConcurrently(EventGraph.Concurrently<T> graph) {
    final var res = new ArrayList<EventGraph<T>>();
    if (graph.left() instanceof EventGraph.Concurrently<T> l) {
      res.addAll(expandConcurrently(l));
    } else {
      res.add(graph.left());
    }
    if (graph.right() instanceof EventGraph.Concurrently<T> r) {
      res.addAll(expandConcurrently(r));
    } else {
      res.add(graph.right());
    }
    return res;
  }

  public static History atom(String s) {
    final var res = new History();
    res.timeline.add(new TimePoint.Commit(EventGraph.atom(s)));
    return res;
  }

  public static History atom(Duration duration) {
    final var res = new History();
    res.timeline.add(new TimePoint.Delay(duration));
    return res;
  }

  public static CellId<MutableObject<History>> allocate(final Initializer builder, final Topic<String> topic)
  {
    return builder.allocate(
        new MutableObject<>(empty()),
        new CellType<>() {
          @Override
          public EffectTrait<History> getEffectType() {
            return new EffectTrait<>() {
              @Override
              public History empty() {
                return History.empty();
              }

              @Override
              public History sequentially(final History prefix, final History suffix) {
                return History.sequentially(prefix, suffix);
              }

              @Override
              public History concurrently(final History left, final History right) {
                return History.concurrently(left, right);
              }
            };
          }

          @Override
          public MutableObject<History> duplicate(final MutableObject<History> mutableObject) {
            return new MutableObject<>(mutableObject.getValue());
          }

          @Override
          public void apply(final MutableObject<History> mutableObject, final History o) {
            mutableObject.setValue(sequentially(mutableObject.getValue(), o));
          }

          @Override
          public void step(final MutableObject<History> mutableObject, final Duration duration) {
            mutableObject.setValue(sequentially(
                mutableObject.getValue(),
                atom(duration)));
          }
        },
        History::atom,
        topic);
  }

  @Override
  public boolean equals(final Object object) {
    if (this == object) return true;
    if (object == null || getClass() != object.getClass()) return false;

    History history = (History) object;
    return history.toString().equals(this.toString());
  }

  @Override
  public int hashCode() {
    return timeline.hashCode();
  }

  public String toString() {
    final var res = new StringBuilder();
    var first = true;
    for (final var entry : timeline) {
      if (!first) {
        res.append(", ");
      }
      switch (entry) {
        case TimePoint.Commit e -> {
          res.append(e.graph.toString());
        }
        case TimePoint.Delay e -> {
          res.append("delay(");
          res.append(e.duration.in(SECONDS));
          res.append(")");
        }
      }
      first = false;
    }
    return res.toString();
  }

  sealed interface TimePoint {
    record Commit(EventGraph<String> graph) implements TimePoint {}

    record Delay(Duration duration) implements TimePoint {}
  }
}
