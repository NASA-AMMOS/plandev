package gov.nasa.jpl.aerie.permissions.exceptions;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import gov.nasa.jpl.aerie.json.FormattedError;

import java.io.IOException;

/**
 * Class for formatting exceptions thrown while checking permissions into JSON objects
 * that meet the Aerie HTTP endpoint error message format
 * Relevant ticket going over said format: https://github.com/NASA-AMMOS/aerie/issues/1732
 */
@JsonSerialize(using = FormattedError.FormattedErrorSerializer.class)
public final class PermissionsFormattedError extends FormattedError{
  // NoSuchX
  public PermissionsFormattedError(NoSuchPlanException npe) {
    super(AerieService.PERMISSIONS_SERVICE,
          "NO_SUCH_PLAN",
          "Could not check permissions on plan %d: plan does not exist.".formatted(npe.id.id()),
          npe);
  }

  public PermissionsFormattedError(NoSuchSchedulingSpecificationException nsse) {
    super(AerieService.PERMISSIONS_SERVICE,
          "NO_SUCH_SCHEDULING_SPECIFICATION",
          "Could not check permissions on scheduling specification %d: specification does not exist.".formatted(nsse.id.id()),
          nsse);
  }

  public PermissionsFormattedError(NoSuchWorkspaceException nse) {
    super(AerieService.PERMISSIONS_SERVICE,
          "NO_SUCH_WORKSPACE",
          "Could not check permissions on workspace %d: workspace does not exist.".formatted(nse.id.id()),
          nse);
  }

  // IOException
  public PermissionsFormattedError(IOException ioe) {
    super(AerieService.PERMISSIONS_SERVICE, "PERMISSIONS_SERVICE_EXCEPTION", "Could not check permissions.", ioe);
  }

  // Forbidden
  public PermissionsFormattedError(Forbidden f) {
    super(AerieService.PERMISSIONS_SERVICE, "FORBIDDEN", f);
  }

  // PermissionsServiceException
  public PermissionsFormattedError(GraphQLServiceException pse) {
    super(AerieService.PERMISSIONS_SERVICE, "GRAPHQL_SERVICE_EXCEPTION", "Could not check permissions.", pse);
  }
}
