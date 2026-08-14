package gov.nasa.jpl.aerie.orchestration.simulation;

import gov.nasa.jpl.aerie.merlin.protocol.types.Duration;

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

public class SimulationExtentConsumer implements Consumer<Duration>, AutoCloseable {
  private Duration lastAcceptedDuration = Duration.ZERO;
  private Duration lastReportedDuration = null;
  private final Timer printTimer;

  /**
   * Create a new SimulationExtentConsumer that prints the simulation time with a minimum frequency.
   * @param periodMillis The minimum gap between simulation extent updates, in milliseconds.
   */
  public SimulationExtentConsumer(final long periodMillis) {
    printTimer = new Timer();
    printTimer.scheduleAtFixedRate(new TimerTask() {
      @Override
      public void run() {
        // Only print if simulation time has progressed.
        if(!lastAcceptedDuration.equals(lastReportedDuration)) {
          System.out.println("Current simulation time: " + lastAcceptedDuration);
          lastReportedDuration = lastAcceptedDuration;
        }
      }
    }, periodMillis, periodMillis);
  }

  /**
   * Create a new SimulationExtentConsumer that consumes but does not print the simulation time.
   */
  public SimulationExtentConsumer() {
    printTimer = new Timer();
  }

  @Override
  public void accept(final Duration duration) {
    lastAcceptedDuration = duration;
  }

  public Duration getLastAcceptedDuration() { return lastAcceptedDuration; }

  @Override
  public void close() {
    printTimer.cancel();
  }
}
