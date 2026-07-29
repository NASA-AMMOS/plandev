package gov.nasa.ammos.plandev.workspace.server;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import gov.nasa.ammos.plandev.json.FormattedError;
import gov.nasa.ammos.plandev.workspace.server.exceptions.FileLockedException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.MalformedRequest;
import gov.nasa.ammos.plandev.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.ammos.plandev.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.ammos.plandev.workspace.server.postgres.NoSuchWorkspaceException;

import javax.json.Json;
import java.util.Optional;

/**
 * Class for formatting Workspace-specific exceptions into JSON objects
 * that meet the Aerie HTTP endpoint error message format
 */
@JsonSerialize(using = FormattedError.FormattedErrorSerializer.class)
final class WorkspaceFormattedError extends FormattedError {
  // NoSuchWorkspace
  public WorkspaceFormattedError(NoSuchWorkspaceException nse) {
    super(
        AerieService.WORKSPACE_SERVER,
        "NO_SUCH_WORKSPACE",
        nse,
        Json.createObjectBuilder()
            .add("workspace_id", nse.getWorkspaceId())
            .build()
    );
  }
  public WorkspaceFormattedError(NoSuchWorkspaceException nse, String message) {
    super(
        AerieService.WORKSPACE_SERVER,
        "NO_SUCH_WORKSPACE",
        message,
        nse,
        Json.createObjectBuilder()
            .add("workspace_id", nse.getWorkspaceId())
            .build()
    );
  }

  // NoSuchFile
  public WorkspaceFormattedError(NoSuchFileException nsf) {
    super(AerieService.WORKSPACE_SERVER, "NO_SUCH_FILE", nsf.getMessage(), nsf);
  }
  public WorkspaceFormattedError(NoSuchFileException nsf, String message) {
    super(AerieService.WORKSPACE_SERVER, "NO_SUCH_FILE", message, nsf);
  }


  // WorkspaceFileOpException
  public WorkspaceFormattedError(WorkspaceFileOpException wfe) {
    super(AerieService.WORKSPACE_SERVER, "FILE_OPERATION_EXCEPTION", wfe);
  }
  public WorkspaceFormattedError(WorkspaceFileOpException wfe, String message) {
    super(AerieService.WORKSPACE_SERVER, "FILE_OPERATION_EXCEPTION", message, wfe);
  }

  // Malformed Request
  public WorkspaceFormattedError(MalformedRequest mr) {
    super(AerieService.WORKSPACE_SERVER, "MALFORMED_REQUEST", mr.getMessage(), mr.getDetails().orElse(null), mr);
  }

  // Locked File
  public WorkspaceFormattedError(FileLockedException fle) {
    super(AerieService.WORKSPACE_SERVER, "FILE_LOCKED", fle);
  }

  public WorkspaceFormattedError(FileLockedException fle, String message) {
    super(AerieService.WORKSPACE_SERVER, "FILE_LOCKED", message, fle);
  }

  /**
   * Build the 412 returned when a file changed under the editor. The details go under the "data" key
   * so the client can drive its conflict-resolution UI.
   * @param reason "conflict" (the file changed) or "deleted" (the file was removed or moved)
   * @param currentETag the file's current token, or null if it no longer exists
   * @param lastEditedBy the user who last edited the file, or null if unknown
   * @param lastEditedAt the time the file was last edited, or null if unknown
   */
  public static FormattedError saveConflict(
      final String reason,
      final String currentETag,
      final String lastEditedBy,
      final String lastEditedAt) {
    final var message = "deleted".equals(reason)
        ? "This file was deleted or moved by another user."
        : "This file was changed by another user since you last loaded it.";
    final var dataBuilder = Json.createObjectBuilder().add("reason", reason);
    if (currentETag != null) {
      dataBuilder.add("currentETag", currentETag);
    } else {
      dataBuilder.addNull("currentETag");
    }
    if (lastEditedBy != null) {
      dataBuilder.add("lastEditedBy", lastEditedBy);
    }
    if (lastEditedAt != null) {
      dataBuilder.add("lastEditedAt", lastEditedAt);
    }
    return new FormattedError(
        AerieService.WORKSPACE_SERVER,
        "SAVE_CONFLICT",
        message,
        Optional.empty(),
        dataBuilder.build()
    );
  }

  @Override
  public String toString() {
    return super.toString();
  }
}
