package gov.nasa.jpl.aerie.scheduler.server.services;

import java.util.Objects;
import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;
import gov.nasa.jpl.aerie.scheduler.server.remotes.postgres.SpecificationRevisionData;

/**
 * details of a scheduling request, including the target schedule specification version and goals to operate on
 *
 * @param specificationId target schedule specification to read as schedule configuration
 * @param specificationRev the revision of the schedule specification and plan when the schedule request was placed (to determine if stale)
 */
public record ScheduleRequest(SpecificationId specificationId, SpecificationRevisionData specificationRev) {
  public ScheduleRequest {
    Objects.requireNonNull(specificationId);
    Objects.requireNonNull(specificationRev);
  }
}
