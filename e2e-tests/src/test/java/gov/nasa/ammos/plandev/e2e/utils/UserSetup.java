package gov.nasa.ammos.plandev.e2e.utils;

import com.microsoft.playwright.Playwright;
import org.jspecify.annotations.NonNull;

import java.io.IOException;

import static gov.nasa.ammos.plandev.e2e.types.User.admin;
import static gov.nasa.ammos.plandev.e2e.types.User.nonOwner;
import static gov.nasa.ammos.plandev.e2e.types.User.owner;
import static gov.nasa.ammos.plandev.e2e.types.User.viewer;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

/**
 * A class that creates all the users shared across E2E Tests prior to any test,
 * then cleans up those users after the tests
 *
 * It is loaded via e2e-tests/src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener
 * For more information, see https://docs.junit.org/6.0.3/advanced-topics/launcher-api.html#launcher-session-listeners-custom
 */
public class UserSetup implements LauncherSessionListener {
  @Override
  public void launcherSessionOpened(LauncherSession session) {
    // Avoid setup for test discovery by delaying it until tests are about to be executed
    session.getLauncher().registerTestExecutionListeners(new TestExecutionListener() {
      @Override
      public void testPlanExecutionStarted(@NonNull TestPlan testPlan) {
        try (final var playwright = Playwright.create();
             final var hasura = new HasuraRequests(playwright)
        ) {
          // Insert the Users
          hasura.createUser(admin);
          hasura.createUser(owner);
          hasura.createUser(nonOwner);
          hasura.createUser(viewer);
        } catch (IOException ioe) {
          throw new RuntimeException(ioe);
        }
      }
    });
  }
}
