package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.BaseURL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class HealthTests {
  private Playwright playwright;
  private APIRequestContext request;

  @BeforeAll
  void beforeAll() {
    playwright = Playwright.create();
    // Because HealthTests check multiple containers, the `request` does not set a base url
    request = playwright.request().newContext();
  }

  @AfterAll
  void afterAll() {
    request.dispose();
    playwright.close();
  }

  @ParameterizedTest
  @EnumSource(BaseURL.class)
  void healthy(BaseURL baseURL) {
    final APIResponse health;
    // Hasura uses `/healthz` instead of `/health` for health checks
    if(baseURL.equals(BaseURL.HASURA)) {
      health = request.get(baseURL.url + "/healthz");
    }
    else {
      health = request.get(baseURL.url + "/health");
    }
    assertTrue(health.ok());
  }
}
