package gov.nasa.ammos.plandev.merlin.server.models;

public record InsertModelInput(int uploadedFileId, String requester, String modelName) {
}
