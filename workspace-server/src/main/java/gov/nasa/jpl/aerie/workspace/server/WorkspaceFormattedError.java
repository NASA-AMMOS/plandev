package gov.nasa.jpl.aerie.workspace.server;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import gov.nasa.jpl.aerie.json.FormattedError;
import gov.nasa.jpl.aerie.workspace.server.exceptions.FileLockedException;
import gov.nasa.jpl.aerie.workspace.server.exceptions.MalformedRequest;
import gov.nasa.jpl.aerie.workspace.server.exceptions.NoSuchFileException;
import gov.nasa.jpl.aerie.workspace.server.exceptions.WorkspaceFileOpException;
import gov.nasa.jpl.aerie.workspace.server.postgres.NoSuchWorkspaceException;

import javax.json.Json;

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

  @Override
  public String toString() {
    return super.toString();
  }
}
