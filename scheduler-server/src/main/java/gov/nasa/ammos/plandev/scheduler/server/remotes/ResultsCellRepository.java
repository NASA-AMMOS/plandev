package gov.nasa.ammos.plandev.scheduler.server.remotes;

import java.util.Optional;
import gov.nasa.ammos.plandev.scheduler.server.ResultsProtocol;
import gov.nasa.ammos.plandev.scheduler.server.models.SpecificationId;
import gov.nasa.ammos.plandev.scheduler.server.services.ScheduleRequest;

public interface ResultsCellRepository {
  ResultsProtocol.OwnerRole allocate(SpecificationId specificationId, final String requestedBy);

  Optional<ResultsProtocol.OwnerRole> claim(long analysisId);

  Optional<ResultsProtocol.ReaderRole> lookup(ScheduleRequest request);

  void deallocate(ResultsProtocol.OwnerRole resultsCell);
}
