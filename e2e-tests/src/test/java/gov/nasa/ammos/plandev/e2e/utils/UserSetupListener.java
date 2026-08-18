package gov.nasa.ammos.plandev.e2e.utils;

import com.microsoft.playwright.Playwright;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_admin;
import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_nonOwner;
import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_owner;
import static gov.nasa.ammos.plandev.e2e.E2ETestSuite.test_viewer;
import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_admin;
import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_nonOwner;
import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_owner;
import static gov.nasa.ammos.plandev.e2e.routes.RoutesTestSuite.routes_viewer;

/**
 * A class that creates all the users shared across E2E Tests prior to any test,
 * then cleans up those users after the tests
 *
 * It is loaded via e2e-tests/src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener
 * For more information, see <a href="https://docs.junit.org/6.0.3/advanced-topics/launcher-api.html#launcher-session-listeners-custom">...</a>
 */
public class UserSetupListener implements LauncherSessionListener {
  public static final UserSetup userSetup = new UserSetup();

  @Override
  public void launcherSessionOpened(LauncherSession session) {
    // Avoid setup for test discovery by delaying it until tests are about to be executed
    session.getLauncher().registerTestExecutionListeners(userSetup);
  }

  public static class UserSetup implements TestExecutionListener {
    private final AtomicBoolean routesSetupComplete = new AtomicBoolean(false);
    private final AtomicBoolean standardSetupComplete = new AtomicBoolean(false);
    private final AtomicBoolean routesTeardownComplete = new AtomicBoolean(false);
    private final AtomicBoolean standardTeardownComplete = new AtomicBoolean(false);

    @Override
    public void testPlanExecutionStarted(@NonNull TestPlan testPlan) {
      /*
       * Only run setup code if we are running tests not in a suite (ie, a dev is running an individual test/test file)
       * If we are running a test suite, the suite is expected to perform the needed setup/teardown
       * in its @BeforeSuite and @AfterSuite methods
       */
      if(testPlan.getChildren(UniqueId.forEngine("junit-jupiter")).isEmpty()) {
        return;
      }

      try (final var playwright = Playwright.create(); final var hasura = new HasuraRequests(playwright)) {
        // Setup users based on test tags
        for (final TestIdentifier child : testPlan.getChildren(UniqueId.forEngine("junit-jupiter"))) {
          // If at least one of the tests has the routes tag, set up the routes users in the DB
          if(child.getTags().contains(TestTag.create("routes"))) {
            setupRoutesUsers(hasura);
          } else {
            // If there are standard tests included, set up the standard users in the DB
            setupStandardUsers(hasura);
          }
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    @Override
    public void testPlanExecutionFinished(@NonNull TestPlan testPlan) {
      try (final var playwright = Playwright.create(); final var hasura = new HasuraRequests(playwright)) {
        // Run the teardown code based on what was set up
        if(routesSetupComplete.getAcquire()) {
          teardownRoutesUsers(hasura);
        }
        if(standardSetupComplete.getAcquire()) {
          teardownStandardUsers(hasura);
        }
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }

    /**
     * Setup and teardown methods for test users.
     * These methods are synchronized so that in the event that there are multiple TestPlans running in parallel
     * that want the same users, the action only occurs once.
     */
    public synchronized void setupRoutesUsers(HasuraRequests hasura) throws IOException {
      if (routesSetupComplete.getAcquire()) return;
      hasura.createUser(routes_admin);
      hasura.createUser(routes_owner);
      hasura.createUser(routes_nonOwner);
      hasura.createUser(routes_viewer);
      routesSetupComplete.setRelease(true);
    }

    public synchronized void teardownRoutesUsers(HasuraRequests hasura) throws IOException {
      if (routesTeardownComplete.getAcquire()) return;
      hasura.deleteUser(routes_admin);
      hasura.deleteUser(routes_owner);
      hasura.deleteUser(routes_nonOwner);
      hasura.deleteUser(routes_viewer);
      routesTeardownComplete.setRelease(true);
    }

    public synchronized void setupStandardUsers(HasuraRequests hasura) throws IOException {
      if (standardSetupComplete.getAcquire()) return;
      hasura.createUser(test_admin);
      hasura.createUser(test_owner);
      hasura.createUser(test_nonOwner);
      hasura.createUser(test_viewer);
      standardSetupComplete.setRelease(true);
    }

    public synchronized void teardownStandardUsers(HasuraRequests hasura) throws IOException {
      if (standardTeardownComplete.getAcquire()) return;
      hasura.deleteUser(test_admin);
      hasura.deleteUser(test_owner);
      hasura.deleteUser(test_nonOwner);
      hasura.deleteUser(test_viewer);
      standardTeardownComplete.setRelease(true);
    }
  }
}
