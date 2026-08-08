package gov.nasa.jpl.aerie.e2e.routes;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.HealthTests;
import gov.nasa.jpl.aerie.e2e.types.User;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import gov.nasa.jpl.aerie.e2e.utils.UserSetupListener;
import org.junit.platform.suite.api.BeforeSuite;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

import java.io.IOException;


/**
 * Test suite for Routes Test files.
 * These tests MUST use one of the docker-compose files in the e2e-tests directory to compose up the system,
 * as only the health check is exposed in the developer or deployment versions of the compose file.
 */
@Suite
@SuiteDisplayName("Route Integration Tests")
@SelectPackages({"gov.nasa.jpl.aerie.e2e.routes"})
@SelectClasses({HealthTests.class})
public class RoutesTestSuite {
  // Users to be shared across the test suite
  public static final User routes_admin = new User(
      "routes_admin",
      "aerie_admin",
      new String[]{"aerie_admin", "viewer"});
  public static final User routes_owner = new User(
      "routes_owner_user",
      "user",
      new String[] {"user"});
  public static final User routes_nonOwner = new User(
      "routes_not_owner_user",
      "user",
      new String[]{"user", "viewer"});
  public static final User routes_viewer = new User(
      "routes_viewer",
      "viewer",
      new String[]{"viewer"});

  /**
   * Login needed test Users prior to executing the suite.
   * UserSetupListener will handle cleanup after all tests are finished
   */
  @BeforeSuite
  static void loginUsers() {
    try (final var playwright = Playwright.create();
         final var hasura = new HasuraRequests(playwright)
    ) {
      UserSetupListener.userSetup.setupRoutesUsers(hasura);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }
}
