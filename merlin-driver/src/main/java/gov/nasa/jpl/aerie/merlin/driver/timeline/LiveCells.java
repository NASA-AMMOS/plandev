package gov.nasa.jpl.aerie.merlin.driver.timeline;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class LiveCells {
  // INVARIANT: Every Query<T> maps to a LiveCell<T>; that is, the type parameters are correlated.
  private final Map<Query<?>, LiveCell<?>> cells = new HashMap<>();
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

  public <State> Optional<State> getState(final Query<State> query) {
    final var cell = getCell(query);
    return cell != null ? Optional.of(cell.getState()) : Optional.empty();
  }

  public Optional<Duration> getExpiry(final Query<?> query) {
    final var cell = getCell(query);
    if (cell == null) return Optional.empty();
    return cell.getExpiry();
  }

  public <State> void put(final Query<State> query, final Cell<State> cell) {
    // SAFETY: The query and cell share the same State type parameter.
    this.cells.put(query, new LiveCell<>(cell, this.source.cursor()));
  }

  /**
   * Returns the cell for the given query, or null if not found.
   * Private method — callers wrap in Optional for the public API.
   */
  private <State> Cell<State> getCell(final Query<State> query) {
    // First, check if we have this cell already.
    {
      // SAFETY: By the invariant, if there is an entry for this query, it is of type Cell<State>.
      @SuppressWarnings("unchecked")
      final var cell = (LiveCell<State>) this.cells.get(query);

      if (cell != null) return cell.get();
    }

    // Otherwise, go ask our parent for the cell.
    if (this.parent == null) return null;
    final var parentCell = this.parent.getCell(query);
    if (parentCell == null) return null;

    final var cell = new LiveCell<>(parentCell.duplicate(), this.source.cursor());

    // SAFETY: The query and cell share the same State type parameter.
    this.cells.put(query, cell);

    return cell.get();
  }

  /**
   * Force-create local LiveCell entries for all cells in the parent.
   * This ensures all cursors exist locally, which is required before timeline trimming
   * can safely advance the head (no new cursors will start from the old head).
   */
  public void materializeAll() {
    if (this.parent == null) return;
    for (final var query : this.parent.allQueries()) {
      getCell(query);
    }
  }

  /**
   * Step up all local cells to the current timeline position.
   * After this call, all cursors have advanced past all current entries.
   */
  public void stepUpAll() {
    for (final var entry : this.cells.values()) {
      entry.get();
    }
  }

  /** Returns the set of all queries known to this LiveCells (local + parent). */
  private Iterable<Query<?>> allQueries() {
    if (this.parent == null) return this.cells.keySet();
    // Combine local and parent queries
    final var all = new HashMap<Query<?>, LiveCell<?>>(this.cells);
    for (final var query : this.parent.allQueries()) {
      all.putIfAbsent(query, null); // null value just to collect keys
    }
    return all.keySet();
  }

  public void freeze() {
    if (this.parent != null) this.parent.freeze();
    this.source.freeze();
  }
}
