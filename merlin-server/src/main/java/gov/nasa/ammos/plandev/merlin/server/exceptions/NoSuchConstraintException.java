package gov.nasa.ammos.plandev.merlin.server.exceptions;

public class NoSuchConstraintException extends Exception {
  public final long id;
  public final long revision;

  public NoSuchConstraintException(final long id, final long revision) {
    super("No constraint exists with id `" + id + "` and revision `" + revision + "`");
    this.id = id;
    this.revision = revision;
  }
}
