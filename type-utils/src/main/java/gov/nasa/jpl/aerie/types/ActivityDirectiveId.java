package gov.nasa.jpl.aerie.types;

public record ActivityDirectiveId(long id) implements ActivityId, Comparable<ActivityDirectiveId> {
  @Override
  public int compareTo(final ActivityDirectiveId o) {
    return Long.compare(this.id, o.id);
  }
}
