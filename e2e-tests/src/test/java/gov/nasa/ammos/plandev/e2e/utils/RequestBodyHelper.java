package gov.nasa.ammos.plandev.e2e.utils;

import com.microsoft.playwright.APIResponse;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import java.io.StringReader;

/**
 * Test the HTTPS Endpoints for the Java Services
 * Health endpoints are already tested in HealthTests
 */
public class RequestBodyHelper {
  /**
   * Get the JSON Object from the Body of an APIResponse
   * @param response APIResponse from a Playwright Request
   * @return the JSON Object representation of the response body
   */
  public static JsonObject getBody(final APIResponse response){
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      return reader.readObject();
    }
  }

  /**
   * Get the JSON Array from the Body of an APIResponse
   * @param response APIResponse from a Playwright Request
   * @return the JSON Array representation of the response body
   */
  public static JsonArray getArrayBody(final APIResponse response){
    try(final var reader = Json.createReader(new StringReader(response.text()))){
      return reader.readArray();
    }
  }
}
