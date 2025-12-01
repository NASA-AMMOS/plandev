package gov.nasa.jpl.plandev.merlin.driver.timeline;

public interface EventSource {
  Cursor cursor();

  void freeze();

  interface Cursor {
    void stepUp(Cell<?> cell);
  }
}
