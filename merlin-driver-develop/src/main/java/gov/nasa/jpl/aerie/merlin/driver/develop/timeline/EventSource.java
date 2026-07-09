package gov.nasa.jpl.aerie.merlin.driver.develop.timeline;

public interface EventSource {
  Cursor cursor();

  void freeze();

  interface Cursor {
    void stepUp(Cell<?> cell);
  }
}
