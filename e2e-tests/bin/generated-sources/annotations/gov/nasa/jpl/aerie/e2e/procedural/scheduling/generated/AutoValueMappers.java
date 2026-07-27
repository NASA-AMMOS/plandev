package gov.nasa.jpl.aerie.e2e.procedural.scheduling.generated;

import gov.nasa.ammos.aerie.procedural.scheduling.plan.DeletedAnchorStrategy;
import gov.nasa.jpl.aerie.contrib.serialization.mappers.RecordValueMapper;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityAutoDeletionGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ActivityDeletionGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.AnchorCascadeDeleteGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DecompositionSchedulingGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DeleteBiteBananasGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.DumbRecurrenceGoalWithTemplateDefaults;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAbsenceConstraint;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventActivityOverlapConstraint;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventAttributeConstraint;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventPresenceConstraint;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeOptionalQueryGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsEventAttributeQueryGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsSimpleGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsSourceAttributeOptionalQueryGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsSourceAttributeQueryGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsSourceQueryGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalEventsTypeQueryGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.ExternalProfileGoal;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.FruitThresholdConstraint;
import gov.nasa.jpl.aerie.e2e.procedural.scheduling.procedures.NoMessageConstraint;
import gov.nasa.jpl.aerie.merlin.framework.ValueMapper;
import java.util.List;
import javax.annotation.processing.Generated;

@Generated("gov.nasa.ammos.aerie.procedural.processor.ProcedureProcessor")
@SuppressWarnings("unchecked")
public final class AutoValueMappers {
  public static ValueMapper<ExternalEventActivityOverlapConstraint> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventActivityOverlapConstraint(
      ) {
    return new RecordValueMapper<>(
      ExternalEventActivityOverlapConstraint.class,
      List.of(
      ));
  }

  public static ValueMapper<ExternalEventsEventAttributeOptionalQueryGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsEventAttributeOptionalQueryGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalEventsEventAttributeOptionalQueryGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<DumbRecurrenceGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_DumbRecurrenceGoal(
      final ValueMapper<Integer> quantity_ValueMapper,
      final ValueMapper<Integer> biteSize_ValueMapper) {
    return new RecordValueMapper<>(
      DumbRecurrenceGoal.class,
      List.of(
        new RecordValueMapper.Component<>(
          "quantity",
          DumbRecurrenceGoal::quantity,
          quantity_ValueMapper),
        new RecordValueMapper.Component<>(
          "biteSize",
          DumbRecurrenceGoal::biteSize,
          biteSize_ValueMapper)));
  }

  public static ValueMapper<AnchorCascadeDeleteGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_AnchorCascadeDeleteGoal(
      ) {
    return new RecordValueMapper<>(
      AnchorCascadeDeleteGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<ExternalEventPresenceConstraint> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventPresenceConstraint(
      final ValueMapper<String> eventType_ValueMapper,
      final ValueMapper<String> derivationGroup_ValueMapper,
      final ValueMapper<String> sourceKey_ValueMapper) {
    return new RecordValueMapper<>(
      ExternalEventPresenceConstraint.class,
      List.of(
        new RecordValueMapper.Component<>(
          "eventType",
          ExternalEventPresenceConstraint::eventType,
          eventType_ValueMapper),
        new RecordValueMapper.Component<>(
          "derivationGroup",
          ExternalEventPresenceConstraint::derivationGroup,
          derivationGroup_ValueMapper),
        new RecordValueMapper.Component<>(
          "sourceKey",
          ExternalEventPresenceConstraint::sourceKey,
          sourceKey_ValueMapper)));
  }

  public static ValueMapper<ActivityDeletionGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ActivityDeletionGoal(
      final ValueMapper<Integer> whichToDelete_ValueMapper,
      final ValueMapper<DeletedAnchorStrategy> anchorStrategy_ValueMapper,
      final ValueMapper<Boolean> rollback_ValueMapper) {
    return new RecordValueMapper<>(
      ActivityDeletionGoal.class,
      List.of(
        new RecordValueMapper.Component<>(
          "whichToDelete",
          ActivityDeletionGoal::whichToDelete,
          whichToDelete_ValueMapper),
        new RecordValueMapper.Component<>(
          "anchorStrategy",
          ActivityDeletionGoal::anchorStrategy,
          anchorStrategy_ValueMapper),
        new RecordValueMapper.Component<>(
          "rollback",
          ActivityDeletionGoal::rollback,
          rollback_ValueMapper)));
  }

  public static ValueMapper<DecompositionSchedulingGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_DecompositionSchedulingGoal(
      ) {
    return new RecordValueMapper<>(
      DecompositionSchedulingGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<FruitThresholdConstraint> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_FruitThresholdConstraint(
      final ValueMapper<Integer> lowerBound_ValueMapper,
      final ValueMapper<Integer> upperBound_ValueMapper) {
    return new RecordValueMapper<>(
      FruitThresholdConstraint.class,
      List.of(
        new RecordValueMapper.Component<>(
          "lowerBound",
          FruitThresholdConstraint::lowerBound,
          lowerBound_ValueMapper),
        new RecordValueMapper.Component<>(
          "upperBound",
          FruitThresholdConstraint::upperBound,
          upperBound_ValueMapper)));
  }

  public static ValueMapper<ExternalEventsSourceQueryGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsSourceQueryGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalEventsSourceQueryGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<DumbRecurrenceGoalWithTemplateDefaults> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_DumbRecurrenceGoalWithTemplateDefaults(
      final ValueMapper<Integer> quantity_ValueMapper,
      final ValueMapper<Integer> biteSize_ValueMapper) {
    return new RecordValueMapper<>(
      DumbRecurrenceGoalWithTemplateDefaults.class,
      List.of(
        new RecordValueMapper.Component<>(
          "quantity",
          DumbRecurrenceGoalWithTemplateDefaults::quantity,
          quantity_ValueMapper),
        new RecordValueMapper.Component<>(
          "biteSize",
          DumbRecurrenceGoalWithTemplateDefaults::biteSize,
          biteSize_ValueMapper)));
  }

  public static ValueMapper<ExternalEventsTypeQueryGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsTypeQueryGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalEventsTypeQueryGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<ActivityAutoDeletionGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ActivityAutoDeletionGoal(
      final ValueMapper<Boolean> deleteAtBeginning_ValueMapper) {
    return new RecordValueMapper<>(
      ActivityAutoDeletionGoal.class,
      List.of(
        new RecordValueMapper.Component<>(
          "deleteAtBeginning",
          ActivityAutoDeletionGoal::deleteAtBeginning,
          deleteAtBeginning_ValueMapper)));
  }

  public static ValueMapper<ExternalEventAttributeConstraint> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventAttributeConstraint(
      final ValueMapper<String> codeValue_ValueMapper) {
    return new RecordValueMapper<>(
      ExternalEventAttributeConstraint.class,
      List.of(
        new RecordValueMapper.Component<>(
          "codeValue",
          ExternalEventAttributeConstraint::codeValue,
          codeValue_ValueMapper)));
  }

  public static ValueMapper<ExternalEventsSimpleGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsSimpleGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalEventsSimpleGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<ExternalEventAbsenceConstraint> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventAbsenceConstraint(
      ) {
    return new RecordValueMapper<>(
      ExternalEventAbsenceConstraint.class,
      List.of(
      ));
  }

  public static ValueMapper<NoMessageConstraint> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_NoMessageConstraint(
      final ValueMapper<Integer> lowerBound_ValueMapper,
      final ValueMapper<Integer> upperBound_ValueMapper) {
    return new RecordValueMapper<>(
      NoMessageConstraint.class,
      List.of(
        new RecordValueMapper.Component<>(
          "lowerBound",
          NoMessageConstraint::lowerBound,
          lowerBound_ValueMapper),
        new RecordValueMapper.Component<>(
          "upperBound",
          NoMessageConstraint::upperBound,
          upperBound_ValueMapper)));
  }

  public static ValueMapper<ExternalEventsSourceAttributeOptionalQueryGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsSourceAttributeOptionalQueryGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalEventsSourceAttributeOptionalQueryGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<ExternalEventsSourceAttributeQueryGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsSourceAttributeQueryGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalEventsSourceAttributeQueryGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<ExternalProfileGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalProfileGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalProfileGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<ExternalEventsEventAttributeQueryGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_ExternalEventsEventAttributeQueryGoal(
      ) {
    return new RecordValueMapper<>(
      ExternalEventsEventAttributeQueryGoal.class,
      List.of(
      ));
  }

  public static ValueMapper<DeleteBiteBananasGoal> gov_nasa_jpl_aerie_e2e_procedural_scheduling_procedures_DeleteBiteBananasGoal(
      final ValueMapper<DeletedAnchorStrategy> anchorStrategy_ValueMapper) {
    return new RecordValueMapper<>(
      DeleteBiteBananasGoal.class,
      List.of(
        new RecordValueMapper.Component<>(
          "anchorStrategy",
          DeleteBiteBananasGoal::anchorStrategy,
          anchorStrategy_ValueMapper)));
  }
}
