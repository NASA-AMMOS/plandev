package gov.nasa.jpl.aerie.merlin.server.mocks;

import gov.nasa.jpl.aerie.merlin.server.remotes.PlanRepositoryContractTest;

public final class InMemoryPlanRepositoryTest extends PlanRepositoryContractTest {
  @Override
  protected void resetRepository() {
    this.planRepository = new InMemoryPlanRepository();
  }
}
