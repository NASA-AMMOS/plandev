package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SubInstantDuration;

import java.util.Arrays;

public final class CausalEventSource implements EventSource {
  public Event[] points = new Event[2];
  private int size = 0;
  private boolean frozen = false;
  private SubInstantDuration timeFroze = null;

  public void add(final Event point) {
    if (this.frozen) {
      throw new IllegalStateException("Cannot add to frozen CausalEventSource");
    }
    if (this.size == this.points.length) {
      this.points = Arrays.copyOf(this.points, 3 * this.size / 2);
    }

    this.points[this.size++] = point;
  }

  public boolean isEmpty() {
    return (this.size == 0);
  }

  // By committing events backward from an endpoint, we can massage the resulting EventGraph
  // into a very linear form that is easy to evaluate: (ev1 ; (ev2 ; (ev3 ; andThen)))
  public EventGraph<Event> commit(EventGraph<Event> andThen) {
    for (var i = this.size; i > 0; i -= 1) {
      andThen = EventGraph.sequentially(EventGraph.atom(this.points[i-1]), andThen);
    }
    return andThen;
  }

  @Override
  public CausalCursor cursor() {
    return new CausalCursor();
  }

  public final class CausalCursor implements Cursor {
    private int index = 0;

    @Override
    public <State> Cell<State> stepUp(final Cell<State> cell) {
      //System.out.println("CausalEventSource.CausalCursor.stepUp(" + cell + "): applying points " + Arrays.toString(points));
      cell.apply(points, this.index, size);
      this.index = size;
      cell.doneStepping = isFrozen();
      return cell;
    }
  }

  @Override
  public void freeze(SubInstantDuration time) {
    this.frozen = true;
    this.timeFroze = time;
  }

  @Override
  public SubInstantDuration timeFroze() {
    return this.timeFroze;
  }
}
