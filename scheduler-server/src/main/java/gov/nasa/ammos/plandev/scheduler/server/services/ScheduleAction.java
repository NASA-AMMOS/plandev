package gov.nasa.jpl.aerie.scheduler.server.services;

import java.io.IOException;
import java.util.Optional;

import gov.nasa.jpl.aerie.scheduler.server.ResultsProtocol;
import gov.nasa.jpl.aerie.scheduler.server.exceptions.NoSuchSpecificationException;
import gov.nasa.jpl.aerie.scheduler.server.models.HasuraAction;
import gov.nasa.jpl.aerie.scheduler.server.models.SpecificationId;

/**
 * represents the query for the results of a scheduling run
 *
 * the query can be run using the scheduling service configured at construction, possibly resulting in the activation of
 * a scheduling agent to produce the results (depending on caching etc)
 *
 * @param specificationService interface to specification service for any necessary specification details
 * @param schedulerService scheduling service that handles activity scheduling requests
 */
//TODO: how to handle successive scheduling requests (probably synchronous per-plan, but not service-wide)
//TODO: how to fetch in-progress status without risking mutating the plan again (since scheduling is NOT idempotent)
public record ScheduleAction(SpecificationService specificationService, SchedulerService schedulerService) {

  /**
   * common interface for different possible results of the query
   */
  //tempting to unify with the overlapping ResultProtocol.State, but kept to parallel merlin...GetSimulationResultsAction
  public sealed interface Response {
    /** Scheduler has enqueued this request. */
    record Pending(long analysisId) implements Response {}

    /**
     * scheduler has not completed running
     */
    record Incomplete(long analysisId) implements Response {}

    /**
     * scheduler completed unsuccessfully, eg in an error or canceled
     */
    record Failed(ScheduleFailure reason, long analysisId) implements Response {}

    /**
     * scheduler completed successfully; contains the requested results
     */
    record Complete(ScheduleResults results, long analysisId, Optional<Long> datasetId) implements Response {}
  }

  /**
   * execute the scheduling operation on the target plan (or retrieve existing scheduling results)
   *
   * @param specificationId identifier of the plan to start scheduling from and to store scheduled output into
   * @return a response object wrapping summary results of the run (either successful or not)
   * @throws NoSuchSpecificationException if the target specification could not be found
   */
  public Response run(final SpecificationId specificationId, final HasuraAction.Session session)
  throws NoSuchSpecificationException, IOException
  {
    //record the plan and specification revision as of the scheduling request time (in case work commences much later in worker thread)
    final var request = new ScheduleRequest(specificationId, this.specificationService.getSpecificationRevisionData(specificationId));

    //submit request to run scheduler workers
    final var response = this.schedulerService.getScheduleResults(request, session.hasuraUserId());

    return repackResponse(response);
  }

  /**
   * convert the response object from the scheduler service into a response for this action
   *
   * @param response the bare scheduling result contents
   * @return scheduling results augmented with any extra post-analysis
   */
  private Response repackResponse(final ResultsProtocol.State response) {
    //the two responses are identical for now, but kept in order to remain parallel to merlin...GetSimulationResultsAction
    if (response instanceof ResultsProtocol.State.Pending r) {
      return new Response.Pending(r.analysisId());
    } else if (response instanceof ResultsProtocol.State.Incomplete r) {
      return new Response.Incomplete(r.analysisId());
    } else if (response instanceof ResultsProtocol.State.Failed r) {
      return new Response.Failed(r.reason(), r.analysisId());
    } else if (response instanceof ResultsProtocol.State.Success r) {
      final var results = r.results();
      //NB: could elaborate with more response content here, like merlin...SimResults adds in violation analytics
      return new Response.Complete(results, r.analysisId(), r.datasetId());
    } else {
      throw new UnexpectedSubtypeError(ResultsProtocol.State.class, response);
    }
  }

}
