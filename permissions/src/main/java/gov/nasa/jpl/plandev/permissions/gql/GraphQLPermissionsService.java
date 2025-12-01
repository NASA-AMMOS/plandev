package gov.nasa.jpl.plandev.permissions.gql;

import gov.nasa.jpl.plandev.permissions.HasuraAction;
import gov.nasa.jpl.plandev.permissions.PlanPermissionType;
import gov.nasa.jpl.plandev.permissions.OwnerOrCollaborator;
import gov.nasa.jpl.plandev.permissions.WorkspaceAction;
import gov.nasa.jpl.plandev.permissions.WorkspacePermissionType;
import gov.nasa.jpl.plandev.permissions.exceptions.Forbidden;
import gov.nasa.jpl.plandev.permissions.exceptions.NoSuchPlanException;
import gov.nasa.jpl.plandev.permissions.exceptions.NoSuchSchedulingSpecificationException;
import gov.nasa.jpl.plandev.permissions.exceptions.NoSuchWorkspaceException;
import gov.nasa.jpl.plandev.permissions.exceptions.PermissionsServiceException;

import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

/**
 * {@inheritDoc}
 *
 * @param graphqlURI endpoint of the merlin graphql service that should be used to access all data
 */
public record GraphQLPermissionsService(
    URI graphqlURI,
    String hasuraGraphQlAdminSecret)
{

  /**
   * timeout for http graphql requests issued to plandev
   */
  private static final java.time.Duration httpTimeout = java.time.Duration.ofSeconds(60);

  /**
   * dispatch the given graphql request to hasura and collect the results
   *
   * absorbs any io errors and returns an empty response object in order to keep exception
   * signature of callers cleanly matching the MerlinService interface
   *
   * @param query the graphQL query or mutation to send to plandev
   * @return the json response returned by plandev, or an empty optional in case of io errors
   */
  private Optional<JsonObject> postRequest(final String query, final JsonObject variables) throws IOException, PermissionsServiceException
  {
    try {
      //TODO: (mem optimization) use streams here to avoid several copies of strings
      final var reqBody = Json
          .createObjectBuilder()
          .add("query", query)
          .add("variables", variables)
          .build();
      final var httpReq = HttpRequest
          .newBuilder().uri(graphqlURI).timeout(httpTimeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("Origin", graphqlURI.toString())
          .header("x-hasura-admin-secret", hasuraGraphQlAdminSecret)
          .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()))
          .build();
      final var httpResp = HttpClient
          .newHttpClient().send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
      if (httpResp.statusCode() != 200) {
        throw new IOException("Unexpected " + httpResp.statusCode() + " status when connecting to hasura");
      }
      final var respBody = Json.createReader(httpResp.body()).readObject();
      if (respBody.containsKey("errors")) {
        throw new PermissionsServiceException(respBody.toString(), respBody.get("errors"));
      }
      return Optional.of(respBody);
    } catch (final InterruptedException e) {
      return Optional.empty();
    } catch (final JsonException e) { // or also JsonParsingException
      throw new IOException("json parse error on graphql response:" + e.getMessage(), e);
    }
  }

  public PlanPermissionType getActionPermission(final HasuraAction action, final String role)
  throws IOException, Forbidden, PermissionsServiceException {
    final var query = """
        query getActionPermission($role: user_roles_enum!, $action: String!) {
          check: user_role_permission_by_pk(role: $role) {
            permission: action_permissions(path: $action)
          }
        }
        """;
    final var variables = Json.createObjectBuilder()
                              .add("action", action.toString())
                              .add("role", role)
                              .build();

    final var response = postRequest(query, variables).orElseThrow(() -> new Forbidden(role, action));
    final var check = response.getJsonObject("data").getJsonObject("check");
    if (check.isNull("permission")) { throw new Forbidden(role, action); }
    return PlanPermissionType.valueOf(check.getString("permission"));
  }

  public WorkspacePermissionType getWorkspaceActionPermission(final WorkspaceAction action, final String role)
  throws IOException, Forbidden, PermissionsServiceException {
    final var query = """
        query getWorkspaceActionPermission($role: user_roles_enum!, $action: String!) {
          check: user_role_permission_by_pk(role: $role) {
            permission: workspace_permissions(path: $action)
          }
        }
        """;
    final var variables = Json.createObjectBuilder()
                              .add("action", action.toString())
                              .add("role", role)
                              .build();

    final var response = postRequest(query, variables).orElseThrow(() -> new Forbidden(role, action));
    final var check = response.getJsonObject("data").getJsonObject("check");
    if (check.isNull("permission")) { throw new Forbidden(role, action); }
    return WorkspacePermissionType.valueOf(check.getString("permission"));
  }

  public OwnerOrCollaborator checkPlanOwnerCollaborator(final PlanId planId, final String username) throws IOException, NoSuchPlanException, PermissionsServiceException {
    final var query = """
        query getPlanOwnerCollaborators($id: Int!, $username: String!) {
          plan: plan_by_pk(id: $id) {
            owner
            collaborators(where: {collaborator: {_eq: $username}}) {
              collaborator
            }
          }
        }
        """;
    final var variables = Json.createObjectBuilder()
                              .add("id", planId.id())
                              .add("username", username)
                              .build();

    final var response = postRequest(query, variables)
        .orElseThrow(() -> new NoSuchPlanException(planId))
        .getJsonObject("data");

    if (response.isNull("plan")) throw new NoSuchPlanException(planId);

    return getOwnerCollaboratorStatus(response.getJsonObject("plan"), username);
  }

  public OwnerOrCollaborator checkWorkspaceOwnerCollaborator(final WorkspaceId workspaceId, final String username)
  throws IOException, NoSuchWorkspaceException, PermissionsServiceException {
    final var query = """
        query getWorkspaceOwnerCollaborators($id: Int!, $username: String!) {
          workspace: workspace_by_pk(id: $id) {
            owner
            collaborators(where: {collaborator: {_eq: $username}}) {
              collaborator
            }
          }
        }
        """;
    final var variables = Json.createObjectBuilder()
                              .add("id", workspaceId.id())
                              .add("username", username)
                              .build();

    final var response = postRequest(query, variables)
        .orElseThrow(() -> new NoSuchWorkspaceException(workspaceId))
        .getJsonObject("data");

    if (response.isNull("workspace")) throw new NoSuchWorkspaceException(workspaceId);

    return getOwnerCollaboratorStatus(response.getJsonObject("workspace"), username);
  }

  /**
   * Extract from the "owner-collaborator" json object
   * whether the given user is the owner, a collaborator, both, or neither
   */
  private OwnerOrCollaborator getOwnerCollaboratorStatus(JsonObject ownerCollaborators, String username){
    final boolean isOwner = username.equals(ownerCollaborators.getString("owner"));
    final boolean isCollaborator = !ownerCollaborators.getJsonArray("collaborators").isEmpty();

    if (isOwner && isCollaborator) return OwnerOrCollaborator.OWNER_AND_COLLABORATOR;
    if (isOwner) return OwnerOrCollaborator.ONLY_OWNER;
    if (isCollaborator) return OwnerOrCollaborator.ONLY_COLLABORATOR;
    return OwnerOrCollaborator.NEITHER;
  }

  public boolean checkMissionModelOwner(final PlanId planId, final String username)
  throws PermissionsServiceException, IOException, NoSuchPlanException
  {
    final var query = """
        query getModelOwner($id: Int!) {
          plan: plan_by_pk(id: $id) {
            mission_model {
              owner
            }
          }
        }
        """;
    final var variables = Json.createObjectBuilder().add("id", planId.id()).build();

    final var response = postRequest(query, variables)
        .orElseThrow(() -> new NoSuchPlanException(planId))
        .getJsonObject("data");

    if (response.isNull("plan")) throw new NoSuchPlanException(planId);

    final var owner = response.getJsonObject("plan")
                              .getJsonObject("mission_model")
                              .getString("owner");

    return username.equals(owner);
  }

  public PlanId getPlanIdFromSchedulingSpecificationId(final SchedulingSpecificationId specificationId)
  throws PermissionsServiceException, IOException, NoSuchSchedulingSpecificationException
  {
    final var query = """
        query planIdFromSpecId($id: Int!) {
          spec: scheduling_specification_by_pk(id: $id) {
            plan_id
          }
        }
        """;
    final var variables = Json.createObjectBuilder().add("id", specificationId.id()).build();

    final var response = postRequest(query, variables)
        .orElseThrow(() -> new NoSuchSchedulingSpecificationException(specificationId))
        .getJsonObject("data");

    if (response.isNull("spec")) throw new NoSuchSchedulingSpecificationException(specificationId);

    final long planId = response.getJsonObject("spec")
                                .getJsonNumber("plan_id")
                                .longValue();
    return new PlanId(planId);
  }
}
