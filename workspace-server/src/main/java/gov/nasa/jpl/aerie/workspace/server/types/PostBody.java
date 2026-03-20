package gov.nasa.jpl.aerie.workspace.server.types;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;

public record PostBody(
    int sourceWorkspaceId,
    int destinationWorkspaceId,
    Path destinationPath,
    PostActions action,
    boolean overwrite) {

  public static PostBody fromJson(ObjectNode body, int sourceWorkspaceId) {
    final int destinationWorkspaceId;
    final Path destinationPath;
    final PostActions action;
    final boolean overwrite;

    if (body.has("moveTo") && body.has("copyTo")) {
      throw new IllegalArgumentException("Too many actions specified for a single request.");
    } else if (body.has("moveTo")) {
      action = PostActions.MOVE;
      destinationPath = Path.of(body.get("moveTo").textValue());
    } else if (body.has("copyTo")) {
      action = PostActions.COPY;
      destinationPath = Path.of(body.get("copyTo").textValue());
    } else {
      throw new IllegalArgumentException("No action supplied for request.");
    }

    destinationWorkspaceId = body.has("toWorkspace") && !body.get("toWorkspace").isNull()
        ? body.get("toWorkspace").intValue()
        : sourceWorkspaceId;
    overwrite = body.has("overwrite") && body.get("overwrite").asBoolean(false);

    return new PostBody(sourceWorkspaceId, destinationWorkspaceId, destinationPath, action, overwrite);
  }
}
