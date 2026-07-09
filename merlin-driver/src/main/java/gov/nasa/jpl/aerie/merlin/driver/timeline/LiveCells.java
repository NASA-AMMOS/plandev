package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.driver.Topic;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SubInstantDuration;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class LiveCells {
  // INVARIANT: Every Query<T> maps to a LiveCell<T>; that is, the type parameters are correlated.
  private final Map<Query<?>, LiveCell<?>> cells = new HashMap<>();
  private final Map<Topic<?>, HashSet<LiveCell<?>>> cellsForTopic = new HashMap<>();

  private final EventSource source;
  private final LiveCells parent;

  public LiveCells(final EventSource source) {
    this.source = source;
    this.parent = null;
  }

  public LiveCells(final EventSource source, final LiveCells parent) {
    this.source = source;
    this.parent = parent;
  }

  public int size() {
    return cells.size();
  }

  public <State> Optional<State> getState(final Query<State> query) {
    return getCell(query).map(Cell::getState);
  }

  public Optional<Duration> getExpiry(final Query<?> query) {
    return getCell(query).flatMap(Cell::getExpiry);
  }

  public <State> LiveCell<State> put(final Query<State> query, final Cell<State> cell) {
    // SAFETY: The query and cell share the same State type parameter.
    final var liveCell = new LiveCell<>(cell, this.source.cursor());
    this.cells.put(query, liveCell);
    cell.getTopics().forEach(t -> this.cellsForTopic.computeIfAbsent(t, $ -> new HashSet<>()).add(liveCell));
    return liveCell;
  }

  public Collection<LiveCell<?>> getCells() {
    return cells.values();
  }

  public Set<LiveCell<?>> getCells(final Topic<?> topic) {
    var c4t = cellsForTopic.get(topic);
    if (c4t != null && !c4t.isEmpty()) return c4t; // assumes one cell per topic; TODO: give up on multiple cells per topic and change signature to getCell(topic)->LiveCell ?
    Set<LiveCell<?>> cells = new LinkedHashSet<>();
    if (parent == null) return cells;
    var parentCells = parent.getCells(topic);
    // Need to get the duplicated cell in cells corresponding to each matching parent cell
    for (var c : parentCells) {
      Stream<Query<?>> queries = parent.cells.keySet().stream().filter(q -> parent.cells.get(q).equals(c));
      var newCells = queries.map(q -> {
        // need to call getCell() just to generate the duplicate of the parent cell
        getCell(q);
        // getCell() above returns Cell instead of LiveCell, so we throw that result away and get it directly.
        return this.cells.get(q);
      });
      cells.addAll(newCells.toList());
    }
    return cells;
  }

  private <State> Optional<Cell<State>> getCell(final Query<State> query) {
    Optional<LiveCell<State>> liveCell = getLiveCell(query);
    return liveCell.isPresent() ? Optional.of(liveCell.get().get()) : Optional.empty();
  }

  public <State> Optional<LiveCell<State>> getLiveCell(final Query<State> query) {
    // First, check if we have this cell already.
    {
      // SAFETY: By the invariant, if there is an entry for this query, it is of type Cell<State>.
      @SuppressWarnings("unchecked")
      final var cell = (LiveCell<State>) this.cells.get(query);

      if (cell != null) return Optional.of(cell);
    }

    // Otherwise, go ask our parent for the cell.
    if (this.parent == null) return Optional.empty();
    // First, update the time of the parent source
    boolean isTimeline = source instanceof TemporalEventSource;
    boolean parentIsTimeline = parent.source instanceof TemporalEventSource;
    boolean bothTimeline = isTimeline && parentIsTimeline;
    if (bothTimeline) {
      ((TemporalEventSource)parent.source).setCurTime(((TemporalEventSource)source).curTime());
    }
    if (!parentIsTimeline) {
      SubInstantDuration time = isTimeline ? ((TemporalEventSource)source).curTime() : SubInstantDuration.ZERO;
      parent.source.freeze(time);
    }
    final var cell$ = this.parent.getCell(query);
    if (cell$.isEmpty()) return Optional.empty();

    // Get the parent cell and store a duplicate if it is done stepping in the parent; else return the parent cell so that it can continue stepping
    final LiveCell cell;
    if (TemporalEventSource.freezable &&
        !parent.isCellDoneStepping(cell$.get())) {
      return parent.getLiveCell(query);
    } else {
      final Cell<State> duplicate = cell$.get().duplicate();
      cell = put(query, duplicate);
      // Set the duplicate cell time to the parent cell time
      if (bothTimeline) {
        ((TemporalEventSource)source).putCellTime(duplicate, ((TemporalEventSource)parent.source).getCellTime(cell$.get()));
      }
    }

    return Optional.of(cell);
  }

  public void freeze(SubInstantDuration time) {
    if (this.parent != null) this.parent.freeze(time);
    if (!this.source.isFrozen()) this.source.freeze(time);
  }

  public boolean isCellDoneStepping(Cell<?> cell) {
    return cell.doneStepping;
  }
}
