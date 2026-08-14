package gov.nasa.jpl.aerie.scheduler.server.services;

import gov.nasa.jpl.aerie.scheduler.server.ResultsProtocol;
import gov.nasa.jpl.aerie.scheduler.server.remotes.ResultsCellRepository;

/**
 * services operations at the intersection of plans and scheduling goals; eg scheduling instances to satisfy goals
 *
 * provides both mutation operations to actively improve a plan's goal satisfaction score (eg by inserting activity
 * instances into the plan) and passive queries to ascertain the current satisfaction level of a plan
 */
public record SchedulerService(ResultsCellRepository store) {
  /**
   * schedules activity instances into the target plan in order to further satisfy the associated scheduling goals
   *
   * @param request details of the scheduling request, including the target plan and goals to operate on
   * @return summary of the scheduling run, including goal satisfaction metrics and changes made
   */
  public ResultsProtocol.State getScheduleResults(final ScheduleRequest request, final String requestedBy) {
    final var cell$ = this.store.lookup(request);
    if (cell$.isPresent()) {
      return cell$.get().get();
    } else {
      // Allocate a fresh cell.
      final var cell = this.store.allocate(request.specificationId(), requestedBy);

      // Return the current value of the reader; if it's incomplete, the caller can check it again later.
      return cell.get();
    }
  }
}
