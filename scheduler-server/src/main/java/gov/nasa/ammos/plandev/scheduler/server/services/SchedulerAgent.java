package gov.nasa.jpl.aerie.scheduler.server.services;

import gov.nasa.jpl.aerie.scheduler.server.ResultsProtocol;

import java.util.function.Supplier;

/**
 * agent that can handle posed scheduling requests
 */
public interface SchedulerAgent {

  /**
   * run the scheduling algorithm on the target plan using the associated scheduling goals
   *
   * mutates the target plan when scheduling is complete!
   *
   * any exceptions related to the particulars of a request should be reported as failures to the writer object; only
   * exceptions fatal to the service itself should be propagated out
   *
   * @param request details of scheduling request, including target plan version
   * @param writer object representing the request for scheduling results, including space to store results
   */
  void schedule(
      ScheduleRequest request,
      ResultsProtocol.WriterRole writer,
      Supplier<Boolean> canceledListener,
      int sizeCachedEngineStore
  ) throws InterruptedException;
}
