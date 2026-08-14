package gov.nasa.jpl.aerie.scheduler.constraints.transformers;

import gov.nasa.jpl.aerie.constraints.model.SimulationResults;
import gov.nasa.jpl.aerie.constraints.time.Windows;
import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;
import gov.nasa.jpl.aerie.scheduler.model.Plan;

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
