package gov.nasa.ammos.plandev.workspace.server.exceptions;

/** The file changed since the client loaded it (its If-Match ETag is stale). Any detail may be null. */
public class StaleFileException extends Exception {
  public final String currentETag;
  public final String lastEditedBy;
  public final String lastEditedAt;

  public StaleFileException(String currentETag, String lastEditedBy, String lastEditedAt) {
    super("The file was changed since it was loaded.");
    this.currentETag = currentETag;
    this.lastEditedBy = lastEditedBy;
    this.lastEditedAt = lastEditedAt;
  }
}
