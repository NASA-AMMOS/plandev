package gov.nasa.jpl.aerie.merlin.driver.engine;

import gov.nasa.jpl.aerie.merlin.driver.timeline.CausalEventSource;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Event;
import gov.nasa.jpl.aerie.merlin.driver.timeline.EventGraph;
import gov.nasa.jpl.aerie.merlin.driver.timeline.LiveCells;
import gov.nasa.jpl.aerie.merlin.driver.timeline.Query;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * A TaskFrame describes a task-in-progress, including its current series of events and any jobs that have branched off.
 *
 * <pre>
 *   branches[0].base |-> branches[1].base  ... |-> branches[n].base   |-> tip
 *                    +-> branches[0].job       +-> branches[n-1].job  +-> branches[n].job
 * </pre>
*/
public final class TaskFrame<Job> {
  private record Branch<Job>(CausalEventSource base, LiveCells context, Job job) {}

  public final List<Branch<Job>> branches = new ArrayList<>();
  public CausalEventSource tip = new CausalEventSource();

  private LiveCells previousCells;
  private LiveCells cells;

  private TaskFrame(final LiveCells context) {
    this.previousCells = context;
    this.cells = new LiveCells(this.tip, this.previousCells);
  }

  // Perform a job, then recursively perform any jobs it spawned.
  // Spawned jobs can see any events their parent emitted prior to the job,
  //   so when we accumulate the branches' events back up, we need to make sure to interleave
  //   the shared segments of the parent's history correctly. The diagram at the top of this class
  //   illustrates the idea.
  public static <Job>
  EventGraph<Event> run(final Job job, final LiveCells context, final BiConsumer<Job, TaskFrame<Job>> executor) {
    final var frame = new TaskFrame<Job>(context);
    executor.accept(job, frame);

    var tip = frame.tip.commit(EventGraph.empty());
    for (var i = frame.branches.size(); i > 0; i -= 1) {
      final var branch = frame.branches.get(i - 1);

      final var branchEvents = run(branch.job, branch.context, executor);
      tip = branch.base.commit(EventGraph.concurrently(tip, branchEvents));
    }

    return tip;
  }


  public <State> Optional<State> getState(final Query<State> query) {
    return this.cells.getState(query);
  }

  public Optional<Duration> getExpiry(final Query<?> query) {
    return this.cells.getExpiry(query);
  }

  public void emit(final Event event) {
    this.tip.add(event);
  }

  public void signal(final Job target) {
    if (this.tip.isEmpty()) {
      // If we haven't emitted any events, subscribe the target to the previous branch point instead.
      // This avoids making long chains of LiveCells over segments where no events have actually been accumulated.
      this.branches.add(new Branch<>(new CausalEventSource(), this.previousCells, target));
    } else {
      this.branches.add(new Branch<>(this.tip, this.cells, target));

      this.tip = new CausalEventSource();
      this.previousCells = this.cells;
      this.cells = new LiveCells(this.tip, this.previousCells);
    }
  }
}
