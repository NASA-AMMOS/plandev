package gov.nasa.ammos.aerie.merlin.driver.test.property;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public interface Trace {
  interface Writer {
    Trace.Writer visitLeft(int step);
    Trace.Writer visitRight(int step);
    Trace.Writer spawn(int step);
    Trace.Writer call(int step);
  }

  interface Reader {
    boolean visitedLeft(int step);
    boolean visitedRight(int step);
    Trace.Reader getLeft(int step);
    Trace.Reader getRight(int step);
    Trace.Reader getSpawn(int step);
    Trace.Reader getCall(int step);
  }

  interface Owner extends Trace.Writer, Trace.Reader {}

  final class TraceImpl implements Owner {
    Map<Integer, TraceImpl> lefts = new LinkedHashMap<>();
    Map<Integer, TraceImpl> rights = new LinkedHashMap<>();
    Map<Integer, TraceImpl> children = new LinkedHashMap<>();

    @Override
    public Trace.Writer visitLeft(final int step) {
      return lefts.computeIfAbsent(step, $ -> new TraceImpl());
    }

    @Override
    public Trace.Writer visitRight(final int step) {
      return rights.computeIfAbsent(step, $ -> new TraceImpl());
    }

    @Override
    public Trace.Writer spawn(final int step) {
      return children.computeIfAbsent(step, $ -> new TraceImpl());
    }

    @Override
    public Trace.Writer call(final int step) {
      return children.computeIfAbsent(step, $ -> new TraceImpl());
    }

    @Override
    public boolean visitedLeft(final int step) {
      return lefts.containsKey(step);
    }

    @Override
    public boolean visitedRight(final int step) {
      return rights.containsKey(step);
    }

    @Override
    public Trace.Reader getLeft(final int step) {
      return Objects.requireNonNull(lefts.get(step));
    }

    @Override
    public Trace.Reader getRight(final int step) {
      return Objects.requireNonNull(rights.get(step));
    }

    @Override
    public Trace.Reader getSpawn(final int step) {
      return Objects.requireNonNull(children.get(step));
    }

    @Override
    public Trace.Reader getCall(final int step) {
      return Objects.requireNonNull(children.get(step));
    }
  }
}
