package gov.nasa.jpl.aerie.scheduler.worker.services;

import java.util.ArrayList;
import java.util.Optional;

import gov.nasa.jpl.aerie.scheduler.SchedulingInterruptedException;
import gov.nasa.jpl.aerie.scheduler.server.ResultsProtocol;
import gov.nasa.jpl.aerie.scheduler.server.models.DatasetId;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleFailure;
import gov.nasa.jpl.aerie.scheduler.server.services.ScheduleResults;

class MockResultsProtocolWriter implements ResultsProtocol.WriterRole {
  final ArrayList<Result> results;

  MockResultsProtocolWriter() {
    this.results = new ArrayList<>();
  }

  sealed interface Result {
    record Success(ScheduleResults results, Optional<DatasetId> datasetId) implements Result {}
    record Failure(ScheduleFailure reason) implements Result {}
    record Canceled(ScheduleFailure message) implements Result {}
  }

  @Override
  public void succeedWith(final ScheduleResults results, final Optional<DatasetId> datasetId) {
    this.results.add(new Result.Success(results, datasetId));
  }

  @Override
  public void reportCanceled(final SchedulingInterruptedException e) {
    this.results.add(new Result.Canceled(new ScheduleFailure.Builder().build()));
  }

  @Override
  public void failWith(final ScheduleFailure reason) {
    this.results.add(new Result.Failure(reason));
  }
}
