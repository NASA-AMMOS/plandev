package gov.nasa.ammos.plandev.workspace.server.exceptions;

/** A new revision would be identical to the file's latest one. */
public class RevisionUnchangedException extends Exception {
  public RevisionUnchangedException(String filePath, String latestRevisionName) {
    super("%s already matches its latest revision, %s.".formatted(filePath, latestRevisionName));
  }
}
