package gov.nasa.jpl.aerie.merlin.server.services;

import gov.nasa.jpl.aerie.merlin.server.models.ActivityDirectiveForValidation;
import gov.nasa.jpl.aerie.merlin.server.services.MissionModelService.BulkArgumentValidationResponse;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.IntStream;
import java.util.List;

public record ValidationWorker(LocalMissionModelService missionModelService, int pollingPeriod) {

  private static final Logger logger = LoggerFactory.getLogger(ValidationWorker.class);

  public void workerLoop() {
    logger.info("validation worker starting...");
    while (!Thread.interrupted()) {
      try {
        Thread.sleep(pollingPeriod);

        // get unvalidated directives, batched by mission model id
        final var validationRequests = missionModelService.getUnvalidatedDirectives();
        if (!validationRequests.isEmpty()) {
          logger.debug(
              "queried {} directives that need validations, across {} models",
              validationRequests.size(),
              validationRequests.keySet().size());
        }

        // spin up each mission model once and process all corresponding directive validations
        for (final var entry : validationRequests.entrySet()) {
          final var beginTime = System.nanoTime();

          final var modelId = entry.getKey();
          logger.debug("processing batch for mission model: {}", modelId.toString());

          final var unvalidatedDirectives = entry.getValue();
          final var responses = missionModelService.validateActivityArgumentsBulk(modelId, unvalidatedDirectives);

          // zip together directives and validations, since DB action needs to insert validations for a given directive
          final List<Pair<ActivityDirectiveForValidation, BulkArgumentValidationResponse>> zippedList = zip(unvalidatedDirectives, responses);

          // write validations out to DB
          missionModelService.updateDirectiveValidations(zippedList);

          final var endTime = System.nanoTime();
          final var duration = (endTime - beginTime) / 1_000_000.0;
          logger.debug("processed model batch of size {} in {} ms", unvalidatedDirectives.size(), duration);
        }
      } catch (InterruptedException ex) {
        // we were interrupted, so exit gracefully
        return;
      } catch (Throwable t) {
        // catch all to keep validation thread from dying, which would require a merlin-server restart
        logger.error("Recovering from unexpected error encountered in validation thread: ", t);
      }
    }
  }

  private static <L, R> List<Pair<L, R>> zip(List<L> left, List<R> right) {
    return IntStream.range(0, Math.min(left.size(), right.size()))
                    .mapToObj(i -> Pair.of(left.get(i), right.get(i)))
                    .toList();
  }
}
