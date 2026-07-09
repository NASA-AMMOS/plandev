package gov.nasa.ammos.aerie.simulation.protocol;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;

import java.util.HashSet;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * A schedule is a set of entries, which each represent a directive with a start time and an id
 */
public record Schedule(ArrayList<ScheduleEntry> entries) {
  public record ScheduleEntry(long id, Duration startTime, Directive directive) {
    public Duration startOffset() {
      return startTime;
    }
  }

  @SafeVarargs
  public static Schedule build(Pair<Duration, Directive>... entries) {
    var id = new AtomicLong(0L);
    final var entries$ = new ArrayList<ScheduleEntry>();
    for (final var entry : entries) {
      entries$.add(new ScheduleEntry(id.getAndIncrement(), entry.getLeft(), entry.getRight()));
    }
    return new Schedule(entries$);
  }

  public static Schedule empty() {
    return Schedule.build();
  }

  public Schedule filter(Predicate<ScheduleEntry> predicate) {
    var res = Schedule.empty();
    for (final var entry : entries) {
      if (predicate.test(entry)) {
        res = res.put(entry.id, entry.startTime, entry.directive);
      }
    }
    return res;
  }

  public Schedule delete(long id) {
    return filter($ -> $.id() != id);
  }


  private Schedule put(long id, Duration startTime, Directive directive) {
    final var newEntries = new ArrayList<ScheduleEntry>();
    for (final var entry : this.entries) {
      if (entry.id != id) {
        newEntries.add(entry);
      }
    }
    newEntries.add(new ScheduleEntry(id, startTime, directive));
    return new Schedule(newEntries);
  }

  public Schedule putAll(Schedule other) {
    final var newEntries = new ArrayList<ScheduleEntry>();
    final var reservedIds = new HashSet<Long>();
    for (final var entry : other.entries) {
      reservedIds.add(entry.id);
    }
    for (final var entry : this.entries) {
      if (!reservedIds.contains(entry.id)) {
        newEntries.add(entry);
      }
    }
    newEntries.addAll(other.entries);
    return new Schedule(newEntries);
  }

  public ScheduleEntry get(long id) {
    for (ScheduleEntry entry : entries) {
      if (entry.id() == id) {
        return entry;
      }
    }
    throw new NoSuchElementException();
  }

  public Schedule plus(Schedule other) {
    var newEntries = new ArrayList<ScheduleEntry>();
    var id = 0L;
    for (final var entry : this.entries) {
      newEntries.add(new ScheduleEntry(id++, entry.startTime, entry.directive));
    }
    for (final var entry : other.entries) {
      newEntries.add(new ScheduleEntry(id++, entry.startTime, entry.directive));
    }
    return new Schedule(newEntries);
  }

  public Schedule plus(Duration startTime, String directive) {
    var newEntries = new ArrayList<ScheduleEntry>();
    var id = 0L;
    for (final var entry : this.entries) {
      newEntries.add(new ScheduleEntry(id++, entry.startTime, entry.directive));
    }
    newEntries.add(new ScheduleEntry(id++, startTime, new Directive(directive, Map.of())));
    return new Schedule(newEntries);
  }

  public int size() {
    return entries.size();
  }

  public Schedule setStartTime(long id, Duration newStartTime) {
    final var oldEntry = this.get(id);
    return this.put(oldEntry.id(), newStartTime, oldEntry.directive());
  }

  public Schedule setArg(long id, String newArg) {
    final var oldEntry = this.get(id);
    return this.put(oldEntry.id(), oldEntry.startTime, new Directive(oldEntry.directive.type(), Map.of("value", SerializedValue.of(newArg))));
  }
}
