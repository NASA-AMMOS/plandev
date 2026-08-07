package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.types.User;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.platform.suite.api.AfterSuite;
import org.junit.platform.suite.api.BeforeSuite;
import org.junit.platform.suite.api.ExcludePackages;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

import java.io.IOException;

/**
 * Test suite that includes all E2E tests except the Bindings Tests
 * (which have their own test suite due to needing a separate Docker configuration)
 */
@Suite
@SuiteDisplayName("E2E Tests (excluding Routes tests)")
@SelectPackages({"gov.nasa.jpl.aerie.e2e"})
@ExcludePackages({"gov.nasa.jpl.aerie.e2e.routes"})
public class E2ETestSuite {
  // Standard users to share between the tests
  public static final User test_admin = new User(
      "test_admin_user",
      "aerie_admin",
      new String[]{"aerie_admin", "viewer"});
  public static final User test_owner = new User(
      "test_owner_user",
      "user",
      new String[] {"user"});
  public static final User test_nonOwner = new User(
      "test_not_owner_user",
      "user",
      new String[]{"user", "viewer"});
  public static final User test_viewer = new User(
      "test_viewer",
      "viewer",
      new String[]{"viewer"});

  /**
   * Login test Users prior to executing the suite
   */
  @BeforeSuite
  static void loginUsers() {
    try (final var playwright = Playwright.create();
         final var hasura = new HasuraRequests(playwright)
    ) {
      // Insert the Users
      hasura.createUser(test_admin);
      hasura.createUser(test_owner);
      hasura.createUser(test_nonOwner);
      hasura.createUser(test_viewer);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }

  /**
   * After the suite, remove the test users.
   */
  @AfterSuite
  static void deleteUsers() {
    try (final var playwright = Playwright.create();
         final var hasura = new HasuraRequests(playwright)
    ) {
      // Insert the Users
      hasura.deleteUser(test_admin);
      hasura.deleteUser(test_owner);
      hasura.deleteUser(test_nonOwner);
      hasura.deleteUser(test_viewer);
    } catch (IOException ioe) {
      throw new RuntimeException(ioe);
    }
  }
}
