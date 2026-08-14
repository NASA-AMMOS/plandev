package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

public final class JobSchedule<JobRef, TimeRef extends SchedulingInstant> {
  /** The scheduled time for each upcoming job. */
  private final Map<JobRef, TimeRef> scheduledJobs = new HashMap<>();

  /** A time-ordered queue of all tasks whose resumption time is concretely known. */
  @DerivedFrom("scheduledJobs")
  private final ConcurrentSkipListMap<TimeRef, Set<JobRef>> queue = new ConcurrentSkipListMap<>();

  public void schedule(final JobRef job, final TimeRef time) {
    final var oldTime = this.scheduledJobs.put(job, time);

    if (oldTime != null) removeJobFromQueue(oldTime, job);

    this.queue.computeIfAbsent(time, $ -> new HashSet<>()).add(job);
  }

  public void unschedule(final JobRef job) {
    final var oldTime = this.scheduledJobs.remove(job);
    if (oldTime != null) removeJobFromQueue(oldTime, job);
  }

  private void removeJobFromQueue(TimeRef time, JobRef job) {
    var jobsAtOldTime = this.queue.get(time);
    jobsAtOldTime.remove(job);
    if (jobsAtOldTime.isEmpty()) {
      this.queue.remove(time);
    }
  }

  public Batch<JobRef> extractNextJobs(final Duration maximumTime) {
    if (this.queue.isEmpty()) return new Batch<>(maximumTime, Collections.emptySet());

    final var time = this.queue.firstKey();
    if (time.project().longerThan(maximumTime)) {
      return new Batch<>(maximumTime, Collections.emptySet());
    }

    // Ready all tasks at the soonest task time.
    final var entry = this.queue.pollFirstEntry();
    entry.getValue().forEach(this.scheduledJobs::remove);
    return new Batch<>(entry.getKey().project(), entry.getValue());
  }

  public void clear() {
    this.scheduledJobs.clear();
    this.queue.clear();
  }

  public Optional<Duration> peekNextTime() {
    if(this.queue.isEmpty()) return Optional.empty();
    return Optional.ofNullable(this.queue.firstKey()).map(SchedulingInstant::offsetFromStart);
  }

  public record Batch<JobRef>(Duration offsetFromStart, Set<JobRef> jobs) {}

  public JobSchedule<JobRef, TimeRef> duplicate() {
    final JobSchedule<JobRef, TimeRef> jobSchedule = new JobSchedule<>();
    for (final var entry : this.queue.entrySet()) {
      jobSchedule.queue.put(entry.getKey(), new HashSet<>(entry.getValue()));
    }
    jobSchedule.scheduledJobs.putAll(this.scheduledJobs);
    return jobSchedule;
  }
}
