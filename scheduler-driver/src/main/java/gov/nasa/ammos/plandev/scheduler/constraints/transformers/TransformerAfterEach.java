package gov.nasa.ammos.plandev.scheduler.constraints.transformers;

import gov.nasa.ammos.plandev.constraints.model.SimulationResults;
import gov.nasa.ammos.plandev.constraints.time.Windows;
import gov.nasa.ammos.plandev.merlin.protocol.types.Duration;
import gov.nasa.ammos.plandev.scheduler.model.Plan;

public class TransformerAfterEach implements TimeWindowsTransformer {

  private final Duration dur;

  public TransformerAfterEach(final Duration dur) {
    this.dur = dur;
  }


  @Override
  public Windows transformWindows(final Plan plan, final Windows windows, final SimulationResults simulationResults) {
    var retWin = windows;
    retWin = retWin.not();
    retWin = retWin.removeTrueSegment(0);
    retWin = retWin.shiftEdges(dur, Duration.ZERO);
    return retWin;
  }
}
