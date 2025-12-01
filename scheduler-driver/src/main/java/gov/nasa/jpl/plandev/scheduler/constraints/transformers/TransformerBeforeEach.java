package gov.nasa.jpl.plandev.scheduler.constraints.transformers;

import gov.nasa.jpl.plandev.constraints.model.SimulationResults;
import gov.nasa.jpl.plandev.constraints.time.Windows;
import gov.nasa.jpl.plandev.merlin.protocol.types.Duration;
import gov.nasa.jpl.plandev.scheduler.model.Plan;

public class TransformerBeforeEach implements TimeWindowsTransformer {

  private final Duration dur;

  public TransformerBeforeEach(final Duration dur) {
    this.dur = dur;
  }


  @Override
  public Windows transformWindows(final Plan plan, final Windows windows, final SimulationResults simulationResults) {
    var retWin = windows;
    retWin = retWin.not();
    retWin = retWin.removeTrueSegment(-1);
    retWin = retWin.shiftEdges(Duration.ZERO, Duration.negate(dur));
    return retWin;
  }
}
