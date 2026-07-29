package gov.nasa.ammos.plandev.permissions;

public enum OwnerOrCollaborator {
  NEITHER,
  ONLY_OWNER,
  ONLY_COLLABORATOR,
  OWNER_AND_COLLABORATOR;

  boolean isOwner() {
    return switch(this) {
      case NEITHER, ONLY_COLLABORATOR -> false;
      case ONLY_OWNER, OWNER_AND_COLLABORATOR -> true;
    };
  }

  boolean isCollaborator() {
    return switch(this) {
      case NEITHER, ONLY_OWNER -> false;
      case ONLY_COLLABORATOR, OWNER_AND_COLLABORATOR -> true;
    };
  }

  boolean isOwnerOrCollaborator() {
    return switch(this) {
      case NEITHER -> false;
      case ONLY_OWNER, ONLY_COLLABORATOR, OWNER_AND_COLLABORATOR -> true;
    };
  }
}
