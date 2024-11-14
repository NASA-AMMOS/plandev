package gov.nasa.jpl.aerie.sequence_generation.models.files;

import gov.nasa.jpl.aerie.merlin.framework.annotations.AutoValueMapper;

import java.time.Instant;

@AutoValueMapper.Record
public record OnboardFile(Instant uplinkTime, String contents, String path) {}
