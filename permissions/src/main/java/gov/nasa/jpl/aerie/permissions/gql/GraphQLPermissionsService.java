package gov.nasa.jpl.aerie.permissions.gql;

import gov.nasa.jpl.aerie.permissions.HasuraAction;
import gov.nasa.jpl.aerie.permissions.PlanPermissionType;
import gov.nasa.jpl.aerie.permissions.OwnerOrCollaborator;
import gov.nasa.jpl.aerie.permissions.WorkspaceAction;
import gov.nasa.jpl.aerie.permissions.WorkspacePermissionType;
import gov.nasa.jpl.aerie.permissions.exceptions.Forbidden;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchPlanException;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchSchedulingSpecificationException;
import gov.nasa.jpl.aerie.permissions.exceptions.NoSuchWorkspaceException;
import gov.nasa.jpl.aerie.permissions.exceptions.PermissionsServiceException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
   * timeout for http graphql requests issued to aerie
   */
  private static final java.time.Duration httpTimeout = java.time.Duration.ofSeconds(60);

  private static final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * dispatch the given graphql request to hasura and collect the results
   *
   * absorbs any io errors and returns an empty response object in order to keep exception
   * signature of callers cleanly matching the MerlinService interface
   *
   * @param query the graphQL query or mutation to send to aerie
   * @return the json response returned by aerie, or an empty optional in case of io errors
   */
  private Optional<ObjectNode> postRequest(final String query, final ObjectNode variables) throws IOException, PermissionsServiceException
  {
    try {
      //TODO: (mem optimization) use streams here to avoid several copies of strings
      final var reqBody = JsonNodeFactory.instance.objectNode();
      reqBody.put("query", query);
      reqBody.set("variables", variables);
      final var httpReq = HttpRequest
          .newBuilder().uri(graphqlURI).timeout(httpTimeout)
          .header("Content-Type", "application/json")
          .header("Accept", "application/json")
          .header("Origin", graphqlURI.toString())
          .header("x-hasura-admin-secret", hasuraGraphQlAdminSecret)
          .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(reqBody)))
          .build();
      final var httpResp = HttpClient
          .newHttpClient().send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
      if (httpResp.statusCode() != 200) {
        throw new IOException("Unexpected " + httpResp.statusCode() + " status when connecting to hasura");
      }
      final var respBody = (ObjectNode) objectMapper.readTree(httpResp.body());
      if (respBody.has("errors")) {
        throw new PermissionsServiceException(respBody.toString(), respBody.get("errors"));
      }
      return Optional.of(respBody);
    } catch (final InterruptedException e) {
      return Optional.empty();
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
    final var variables = JsonNodeFactory.instance.objectNode();
    variables.put("action", action.toString());
    variables.put("role", role);

    final var response = postRequest(query, variables).orElseThrow(() -> new Forbidden(role, action));
    final var check = (ObjectNode) ((ObjectNode) response.get("data")).get("check");
    if (check.get("permission").isNull()) { throw new Forbidden(role, action); }
    return PlanPermissionType.valueOf(check.get("permission").textValue());
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
    final var variables = JsonNodeFactory.instance.objectNode();
    variables.put("action", action.toString());
    variables.put("role", role);

    final var response = postRequest(query, variables).orElseThrow(() -> new Forbidden(role, action));
    final var check = (ObjectNode) ((ObjectNode) response.get("data")).get("check");
    if (check.get("permission").isNull()) { throw new Forbidden(role, action); }
    return WorkspacePermissionType.valueOf(check.get("permission").textValue());
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
    final var variables = JsonNodeFactory.instance.objectNode();
    variables.put("id", planId.id());
    variables.put("username", username);

    final var response = (ObjectNode) postRequest(query, variables)
        .orElseThrow(() -> new NoSuchPlanException(planId))
        .get("data");

    if (response.get("plan").isNull()) throw new NoSuchPlanException(planId);

    return getOwnerCollaboratorStatus((ObjectNode) response.get("plan"), username);
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
    final var variables = JsonNodeFactory.instance.objectNode();
    variables.put("id", workspaceId.id());
    variables.put("username", username);

    final var response = (ObjectNode) postRequest(query, variables)
        .orElseThrow(() -> new NoSuchWorkspaceException(workspaceId))
        .get("data");

    if (response.get("workspace").isNull()) throw new NoSuchWorkspaceException(workspaceId);

    return getOwnerCollaboratorStatus((ObjectNode) response.get("workspace"), username);
  }

  /**
   * Extract from the "owner-collaborator" json object
   * whether the given user is the owner, a collaborator, both, or neither
   */
  private OwnerOrCollaborator getOwnerCollaboratorStatus(ObjectNode ownerCollaborators, String username){
    final boolean isOwner = username.equals(ownerCollaborators.get("owner").textValue());
    final boolean isCollaborator = !ownerCollaborators.get("collaborators").isEmpty();

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
    final var variables = JsonNodeFactory.instance.objectNode();
    variables.put("id", planId.id());

    final var response = (ObjectNode) postRequest(query, variables)
        .orElseThrow(() -> new NoSuchPlanException(planId))
        .get("data");

    if (response.get("plan").isNull()) throw new NoSuchPlanException(planId);

    final var owner = ((ObjectNode) ((ObjectNode) response.get("plan"))
                              .get("mission_model"))
                              .get("owner").textValue();

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
    final var variables = JsonNodeFactory.instance.objectNode();
    variables.put("id", specificationId.id());

    final var response = (ObjectNode) postRequest(query, variables)
        .orElseThrow(() -> new NoSuchSchedulingSpecificationException(specificationId))
        .get("data");

    if (response.get("spec").isNull()) throw new NoSuchSchedulingSpecificationException(specificationId);

    final long planId = ((ObjectNode) response.get("spec"))
                                .get("plan_id")
                                .longValue();
    return new PlanId(planId);
  }
}
