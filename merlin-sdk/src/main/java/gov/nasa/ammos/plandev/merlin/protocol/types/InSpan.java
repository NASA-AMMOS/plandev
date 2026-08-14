package gov.nasa.jpl.aerie.merlin.protocol.types;

public enum InSpan {
  /**
   * Spawn a child task into the same span as its parent.
   */
  Parent,
  /**
   * Spawn a child task into a fresh span under its parent's span.
   */
  Fresh
}
