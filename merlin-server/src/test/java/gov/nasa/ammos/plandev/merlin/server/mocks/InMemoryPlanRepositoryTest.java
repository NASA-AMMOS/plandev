package gov.nasa.ammos.plandev.merlin.server.mocks;

import gov.nasa.ammos.plandev.merlin.server.remotes.PlanRepositoryContractTest;

public final class InMemoryPlanRepositoryTest extends PlanRepositoryContractTest {
  @Override
  protected void resetRepository() {
    this.planRepository = new InMemoryPlanRepository();
  }
}
