package gov.nasa.jpl.aerie.workspace.server;

import gov.nasa.jpl.aerie.workspace.server.exceptions.MalformedRequest;
import gov.nasa.jpl.aerie.workspace.server.types.MetadataKeys;

import javax.json.JsonObject;
import java.time.Instant;
import java.util.Optional;

public class MetadataUpdates {
  // System-Managed Fields
  private final Optional<String> version;
  // Stable per-file identity used by file versioning. Set once (at first revision/migration) and
  // preserved across saves so a file's history survives renames. System-managed; not user-settable.
  private final Optional<String> fileId;
  private final Optional<String> createdBy;
  private final Optional<Instant> createdAt;
  private final Optional<String> lastEditedBy;
  private final Optional<Instant> lastEditedAt;
  // Fallback values. Non-optional as we know when the most recent edit to the metadata is:
  // it's when the edits represented by this Update object are applied.
  private final String metadataLastEditedBy;
  private final Instant metadataLastEditedAt;
  // User-Managed Fields
  private final Optional<Boolean> readOnly;
  private final Optional<JsonObject> user;

  private MetadataUpdates(Builder builder){
    this.version = Optional.ofNullable(builder.version);
    this.fileId = Optional.ofNullable(builder.fileId);
    this.createdBy = Optional.ofNullable(builder.createdBy);
    this.createdAt = Optional.ofNullable(builder.createdAt);
    this.lastEditedBy = Optional.ofNullable(builder.lastEditedBy);
    this.lastEditedAt = Optional.ofNullable(builder.lastEditedAt);
    // Tracked but unwritten fields (currently used for fallbacks)
    this.metadataLastEditedBy = builder.metadataLastEditedBy;
    this.metadataLastEditedAt = builder.metadataLastEditedAt;

    this.readOnly = Optional.ofNullable(builder.readOnly);
    this.user = Optional.ofNullable(builder.user);
  }

  private MetadataUpdates(String userId, Optional<Boolean> readOnly, Optional<JsonObject> user) {
    this.metadataLastEditedBy = userId;
    this.metadataLastEditedAt = Instant.now();
    this.readOnly = readOnly;
    this.user = user;

    this.version = Optional.empty();
    this.fileId = Optional.empty();
    this.createdBy = Optional.empty();
    this.createdAt = Optional.empty();
    this.lastEditedBy = Optional.empty();
    this.lastEditedAt = Optional.empty();
  }

  // System-managed fields
  public Optional<String> version() {
    return version;
  }

  public Optional<String> fileId() {
    return fileId;
  }

  public Optional<String> createdBy() {
    return createdBy;
  }

  public Optional<Instant> createdAt() {
    return createdAt;
  }

  public Optional<String> lastEditedBy() {
    return lastEditedBy;
  }

  public Optional<Instant> lastEditedAt() {
    return lastEditedAt;
  }

  public String metadataLastEditedBy() {
    return metadataLastEditedBy;
  }

  public Instant metadataLastEditedAt() {
    return metadataLastEditedAt;
  }

  // User-managed fields
  public Optional<Boolean> readOnly() {
    return readOnly;
  }

  public Optional<JsonObject> user() {
    return user;
  }

  public static MetadataUpdates fromEndpointBodyJson(String userId, JsonObject body) throws MalformedRequest {
    // Check the fields to be updated against the white list
    final Optional<Boolean> readOnly;
    final Optional<JsonObject> user;

    if (!MetadataKeys.whitelist.containsAll(body.keySet())) {
      throw new MalformedRequest("Request body contains unpermitted keys. "
                                 + "Only the following keys may be updated: "
                                 + String.join(", ", MetadataKeys.whitelist));
    }

    // Check that the "readOnly" field, if present, is a boolean
    if (body.containsKey("readOnly")) {
      try {
        readOnly = Optional.of(body.getBoolean("readOnly"));
      } catch (NullPointerException npe) {
        throw new MalformedRequest("Key 'readOnly' cannot be 'null'");
      } catch (ClassCastException cce) {
        throw new MalformedRequest("Key 'readOnly' must be a boolean");
      }
    } else {
      readOnly = Optional.empty();
    }

    // Check that the "user" object, if present, is:
    // 1) a JSON object
    // 2) does not contain keys that contain the "." character
    if (body.containsKey("user")) {
      if (body.get("user") instanceof JsonObject userObj) {
        if (!validUserKeys(userObj)) {
          throw new MalformedRequest("Keys within the 'user' object contain forbidden character '.'");
        }
        user = Optional.of(userObj);
      } else {
        throw new MalformedRequest("Key 'user' must be a JSON Object.");
      }
    } else {
      user = Optional.empty();
    }

    return new MetadataUpdates(userId, readOnly, user);
  }

  /**
   * Validates that no key or subkey in the 'user' object contains the forbidden character '.'
   *
   * Returns 'false' as soon as the first violation is found. If there are no violations, returns 'true'
   */
  private static boolean validUserKeys(JsonObject obj) {
    for(final var entry : obj.entrySet()) {
      if(entry.getKey().contains(".")) {
        return false;
      } else if(entry.getValue() instanceof JsonObject subObj) {
        if(!validUserKeys(subObj)) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Returns true if this object contains no updates to user-managed fields, false otherwise.
   */
  public boolean noUserUpdates() {
    return readOnly.isEmpty() && user.isEmpty();
  }

  public static class Builder {
    private String version;
    private String fileId;
    private String createdBy;
    private Instant createdAt;
    private String lastEditedBy;
    private Instant lastEditedAt;
    private final String metadataLastEditedBy;
    private final Instant metadataLastEditedAt;

    private Boolean readOnly;
    private JsonObject user;

    public Builder(String userId) {
      this.metadataLastEditedBy = userId;
      this.metadataLastEditedAt = Instant.now();
    }

    public Builder(String userId, Instant lastEditedAt) {
      this.metadataLastEditedBy = userId;
      this.metadataLastEditedAt = lastEditedAt;
    }

    public Builder version(String version) {
      this.version = version;
      return this;
    }

    public Builder fileId(String fileId) {
      this.fileId = fileId;
      return this;
    }

    public Builder createdBy(String createdBy) {
      this.createdBy = createdBy;
      return this;
    }

    public Builder createdAt(Instant createdAt) {
      this.createdAt = createdAt;
      return this;
    }

    public Builder lastEditedBy(String lastEditedBy) {
      this.lastEditedBy = lastEditedBy;
      return this;
    }

    public Builder lastEditedAt(Instant lastEditedAt) {
      this.lastEditedAt = lastEditedAt;
      return this;
    }

    public Builder readOnly(Boolean readOnly) {
      this.readOnly = readOnly;
      return this;
    }

    public Builder user(JsonObject user) throws IllegalArgumentException {
      if(user != null && !validUserKeys(user)) {
        throw new IllegalArgumentException("Keys within the 'user' object contain forbidden character '.'");
      }
      this.user = user;
      return this;
    }

    public MetadataUpdates build() {
      return new MetadataUpdates(this);
    }

    public JsonObject getUser() {
      return user;
    }
  }
}
