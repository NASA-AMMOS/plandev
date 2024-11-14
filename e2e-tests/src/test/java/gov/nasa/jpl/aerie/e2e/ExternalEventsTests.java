package gov.nasa.jpl.aerie.e2e;

import com.microsoft.playwright.Playwright;
import gov.nasa.jpl.aerie.e2e.utils.GatewayRequests;
import gov.nasa.jpl.aerie.e2e.utils.HasuraRequests;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;

/**
 * A set of tests focusing on testing gateway functionality for external sources.
 * These tests verify validation of External Source uploads.
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ExternalEventsTests {
  // Requests
  private Playwright playwright;
  private HasuraRequests hasura;

  @BeforeAll
  void beforeAll() {
    // Setup Requests
    playwright = Playwright.create();
    hasura = new HasuraRequests(playwright);
  }

  // need a method to create external event type
  @Test
  void uploadExternalEventType() throws IOException, InterruptedException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalEventType();
    }
  }

  // need a method to create external source type, not using hasura object
  @Test
  void uploadExternalSourceType() throws IOException, InterruptedException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSourceType();
    }
  }

  // test source type upload
  // test event type upload

  // test that a source goes in including all the attributes
  @Test
  void uploadExternalSource() throws IOException, InterruptedException {
    try (final var gateway = new GatewayRequests(playwright)) {
      gateway.uploadExternalSource();
    }
  }
  // test that a source fails missing an attribute
  // test that a source fails with an extra attribute
  // test that a source fails with an attribute of the wrong type

  // test that an event goes in including all the attributes
  // test that an event fails missing an attribute
  // test that an event fails with an extra attribute
  // test that an event fails with an attribute of the wrong type

  // test that an event fails going into a source if the type isn't allowed
}
