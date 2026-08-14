package gov.nasa.jpl.aerie.orchestration.simulation;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class CanceledListener implements Supplier<Boolean> {
  private final AtomicBoolean canceled = new AtomicBoolean(false);

  public void cancel() { canceled.set(true); }

  @Override
  public Boolean get() {
    return canceled.get();
  }
}
