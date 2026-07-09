package gov.nasa.ammos.aerie.simulation.protocol;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.merlin.protocol.types.SerializedValue;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

public class DualSchedule {
  Schedule schedule;
  List<Edit> edits;

  public sealed interface Edit {
    Schedule apply(Schedule original);

    record UpdateStart(long id, Duration newStartOffset) implements Edit {
      @Override
      public Schedule apply(final Schedule original) {
        return original.setStartTime(id, newStartOffset);
      }
    }
    record UpdateArg(long id, String newArg) implements Edit {
      @Override
      public Schedule apply(final Schedule original) {
        return original.setArg(id, newArg);
      }
    }
    record Delete(long id) implements Edit {
      @Override
      public Schedule apply(final Schedule original) {
        return original.delete(id);
      }
    }
    record Add(Duration startOffset, String directiveType, String arg) implements Edit {
      @Override
      public Schedule apply(final Schedule original) {
        return original.plus(Schedule.build(Pair.of(startOffset, new Directive(directiveType, Map.of("value", SerializedValue.of(arg))))));
      }
    }
  }

  public DualSchedule() {
    schedule = Schedule.empty();
    edits = new ArrayList<>();
  }

  public Modifier add(int seconds, String directiveType) {
    return add(SECONDS.times(seconds), directiveType);
  }

  public Modifier add(int seconds, String directiveType, String arg) {
    return add(SECONDS.times(seconds), directiveType, arg);
  }

  public Modifier add(Duration startOffset, String directiveType) {
    return add(startOffset, directiveType, "");
  }

  public Modifier add(Duration startOffset, String directiveType, String arg) {
    schedule = schedule.plus(Schedule.build(Pair.of(startOffset, new Directive(directiveType, Map.of("value", SerializedValue.of(arg))))));
    final var id = schedule.entries().getLast().id();
    return new Modifier() {
      @Override
      public void thenUpdate(final int newStartOffsetSeconds) {
        thenUpdate(SECONDS.times(newStartOffsetSeconds));
      }

      @Override
      public void thenUpdate(final Duration newStartOffset) {
        edits.add(new Edit.UpdateStart(id, newStartOffset));
      }

      @Override
      public void thenUpdate(final String newArgument) {
        edits.add(new Edit.UpdateArg(id, newArgument));
      }

      @Override
      public void thenDelete() {
        edits.add(new Edit.Delete(id));
      }
    };
  }

  public void thenAdd(int startOffset, String directiveType) {
    thenAdd(SECOND.times(startOffset), directiveType);
  }

  public void thenAdd(int startOffset, String directiveType, String arg) {
    thenAdd(SECOND.times(startOffset), directiveType, arg);
  }

  public void thenAdd(Duration startOffset, String directiveType) {
    thenAdd(startOffset, directiveType, "");
  }
  public void thenAdd(Duration startOffset, String directiveType, String arg) {
    edits.add(new Edit.Add(startOffset, directiveType, arg));
  }

  public void thenDelete(long id) {
    edits.add(new Edit.Delete(id));
  }

  public void thenUpdate(long id, Duration newStartOffset) {
    edits.add(new Edit.UpdateStart(id, newStartOffset));
  }

  public void thenUpdate(long id, String newArgument) {
    edits.add(new Edit.UpdateArg(id, newArgument));
  }

  public interface Modifier {
    void thenUpdate(int newStartOffsetSeconds);
    void thenUpdate(Duration newStartOffset);
    void thenUpdate(String newArgument);
    void thenDelete();
  }

  public Schedule schedule1() {
    schedule.entries().sort(Comparator.comparing(Schedule.ScheduleEntry::startOffset));
    return schedule;
  }

  public Schedule schedule2() {
    var res = schedule;
    for (final var edit : edits) {
      switch (edit) {
        case Edit.Add e -> {
          res = res.plus(Schedule.build(Pair.of(e.startOffset, new Directive(e.directiveType, Map.of("value", SerializedValue.of(e.arg))))));
        }
        case Edit.Delete e -> {
          res = res.delete(e.id);
        }
        case Edit.UpdateStart e -> {
          res = res.setStartTime(e.id, e.newStartOffset);
        }
        case Edit.UpdateArg e -> {
          res = res.setArg(e.id, e.newArg);
        }
      }
    }
    res.entries().sort(Comparator.comparing(Schedule.ScheduleEntry::startOffset));
    return res;
  }

  public List<Pair<Schedule.ScheduleEntry, Edit>> summarize() {
    final var res = new ArrayList<Pair<Schedule.ScheduleEntry, Edit>>();
    final var entriesById = new LinkedHashMap<Long, Schedule.ScheduleEntry>();
    final var editsById = new LinkedHashMap<Long, Edit>();
    final var thenAdds = new ArrayList<Edit.Add>();
    for (final var entry : schedule.entries()) {
      entriesById.put(entry.id(), entry);
    }
    for (final var edit : edits) {
      switch (edit) {
        case Edit.Add e -> {
          thenAdds.add(e);
        }
        case Edit.Delete e -> {
          editsById.put(e.id(), e);
        }
        case Edit.UpdateStart e -> {
          editsById.put(e.id(), e);
        }
        case Edit.UpdateArg e -> {
          editsById.put(e.id(), e);
        }
      }
    }

    for (final var entry : entriesById.entrySet()) {
      final var edit = editsById.get(entry.getKey());
      res.add(Pair.of(entry.getValue(), edit));
    }

    for (final var add : thenAdds) {
      res.add(Pair.of(null, add));
    }

    return res;
  }
}
