package gov.nasa.jpl.aerie.workspace.server.types;

import javax.json.JsonException;
import javax.json.JsonObject;
import java.nio.file.Path;

public record PostBody(
    int sourceWorkspaceId,
    int destinationWorkspaceId,
    Path destinationPath,
    PostActions action,
    boolean overwrite) {

  public static PostBody fromJson(JsonObject body, int sourceWorkspaceId) throws JsonException {
    final int destinationWorkspaceId;
    final Path destinationPath;
    final PostActions action;
    final boolean overwrite;

    if(body.containsKey("moveTo") && body.containsKey("copyTo")) {
        throw new JsonException("Too many actions specified for a single request.");
      } else if(body.containsKey("moveTo")) {
        action = PostActions.MOVE;
        destinationPath = Path.of(body.getString("moveTo"));
      } else if(body.containsKey("copyTo")) {
        action = PostActions.COPY;
        destinationPath = Path.of(body.getString("copyTo"));
      } else {
        throw new JsonException("No action supplied for request.");
      }

      destinationWorkspaceId = body.getInt("toWorkspace", sourceWorkspaceId);
      overwrite = body.getBoolean("toWorkspace", false);

      return new PostBody(sourceWorkspaceId, destinationWorkspaceId, destinationPath, action, overwrite);
  }
}
