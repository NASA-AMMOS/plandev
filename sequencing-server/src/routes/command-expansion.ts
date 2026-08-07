import type { Context } from '../app.js';
import { db } from '../app.js';
import express from 'express';
import getLogger from '../utils/logger.js';
import { getUsername } from '../utils/hasura.js';
import type { SimulatedActivity } from '../lib/batchLoaders/simulatedActivityBatchLoader.js';
import { Mustache } from '../lib/mustache/util/index.js';
import { seqnBuilder } from '../builders/seqnBuilder.js';
import type { ExpandedActivity, SeqBuilder } from '../types/seqBuilder.js';
import { applyActivityLayerFilter } from '../lib/filters/utilities.js';
import { convertDoyToYmd } from '../lib/mustache/util/time.js';
import { stringifyActivity } from '../lib/mustache/util/activity.js';
import { stolBuilder } from '../builders/stolBuilder.js';
import { concatBuilder } from "../builders/concatBuilder.js";
import { SequencingLanguage } from '../lib/mustache/enums/language.js';

const logger = getLogger('app');

export const commandExpansionRouter = express.Router();

commandExpansionRouter.post('/assign-activities-by-filter', async (req, res, next) => {
  /**
   * ARGUMENTS
   * {
   *    filterId: Int!,
   *    simulationDatasetId: Int!,
   *    seqId: String!
   *    timeRangeStart: String!,
   *    timeRangeEnd: String!
   * }
   */

  // 1. Grab filterId, simulationDatasetId, seqId (for later); load the filter and set of simulated activities
  const context: Context = res.locals['context'];

  const filterId = req.body.input.filterId as number;
  const simulationDatasetId = req.body.input.simulationDatasetId as number;
  const seqId = req.body.input.seqId as string;
  const timeRangeStart = Temporal.Instant.from(convertDoyToYmd(req.body.input.timeRangeStart));
  const timeRangeEnd = Temporal.Instant.from(convertDoyToYmd(req.body.input.timeRangeEnd));

  // Verify that timeRangeStart < timeRangeEnd
  if (timeRangeStart.epochMicroseconds > timeRangeEnd.epochMicroseconds) {
    throw new Error(
      `POST /command-expansion/assign-activities-by-filter: Provided start time (${timeRangeStart.toString()}) greater than end time (${timeRangeEnd.toString()}) for filtration.`,
    );
  }

  const [simulatedActivities, sequenceFilter] = await Promise.all([
    context.simulatedActivitiesDataLoader.load({ simulationDatasetId }),
    context.sequenceFilterDataLoader.load({ filterId })
  ]);

  // 2. Evaluate the filter, creating a set of filtered, simulated activities
  let filteredActivities: SimulatedActivity<Record<string, unknown>, Record<string, unknown>>[] = applyActivityLayerFilter(sequenceFilter.filter, simulatedActivities, timeRangeStart, timeRangeEnd);

  // 3. Create new entries in sequencing.seqeunce_to_simulated_activity for just the filtered, simulated activities and the passed-in seqId
  const { rows } = await db.query(`
      insert into sequencing.sequence_to_simulated_activity (simulated_activity_id, simulation_dataset_id, seq_id)
      select *
      from unnest(
           $1::int[],
           array_fill($2::int, array [array_length($1::int[], 1)]),
           array_fill($3::text, array [array_length($1::int[], 1)])
      )
      returning simulated_activity_id;
`, [
    filteredActivities.map(entry => entry.id),
    simulationDatasetId,
    seqId
  ]);
  if (rows.length < 1) {
    throw new Error(
      `POST /command-expansion/assign-activities-by-filter: Entries failed to be created for filtered activities.`,
    );
  }
  logger.info(`POST /command-expansion/assign-activities-by-filter: Inserted entries for filtered activities.`);

  //    3c. Return
  res.status(200).json({
    success: true
  });
  return next();
})

commandExpansionRouter.post('/put-template', async (req, res, next) => {
  const name = req.body.input.name as string;
  const parcelId = req.body.input.parcelId as number | null;
  const modelId = req.body.input.modelId as number | null;
  const activityTypeName = req.body.input.activityTypeName as string;
  let language = req.body.input.language as SequencingLanguage;
  const username = getUsername(req.body.session_variables, req.headers.authorization);

  // if this makes use of helpers, which is possible, there's no easy way to verify this is valid mustache without
  //    getting accurate sample input.
  //    i.e. if I have a template "CMD {{ data }} " and pass it input={}, I'll get "CMD ", without error. BUT
  //         if I have a template "CMD WHEN={{ clean-date date }}" and pass it input={}, I'll get a failure.
  //    Since this cannot be anticipated ahead of time, we don't pre-compile/verify here.
  const templateDefinition = req.body.input.templateDefinition as string;

  if (modelId == null || parcelId == null) {
    res.status(500).json({ errors: ["Must include parcelId and authoringMissionModelId."] });
    return next();
  }
  if (["stol", "seqn", "text"].indexOf(language.toLowerCase()) === -1) {
    res.status(500).json({ errors: [`Invalid language ${language}; must be "STOL", "SeqN", or "Text".`] });
    return next();
  }

  if (language.toLowerCase() === "stol") language = SequencingLanguage.STOL;
  if (language.toLowerCase() === "seqn") language = SequencingLanguage.SEQN;
  if (language.toLowerCase() === "text") language = SequencingLanguage.TEXT;

  const { rows } = await db.query(
    `
    insert into sequencing.sequence_template (name, model_id, parcel_id, template_definition, activity_type, language, owner)
    values ($1, $2, $3, $4, $5, $6, $7)
    returning id;
  `,
    [name, modelId, parcelId, templateDefinition, activityTypeName, language, username],
  );

  if (rows.length < 1) {
    throw new Error(`POST /put-template: No template was updated in the database`);
  }

  const id = rows[0].id;
  logger.info(`POST /put-template: Updated template in the database: id=${id}`);

  res.status(200).json({ id });
  return next();
});

commandExpansionRouter.post('/expand-all-sequence-templates', async (req, res, next) => {
  /**
   * ARGUMENTS
   * {
   *    modelId: Int!,
   *    simulationDatasetId: Int!,
   *    seqIds: [Int!]!
   * }
   */

  // const defaultTemplate = "CMD {{format-as-date startTime}} {{name}} {{duration}}"; //req.body.input.template;
  const context: Context = res.locals['context'];

  //  0. Extract stuff from request
  // needed to uniquely identify sequence templates, along with activity type
  const modelId = req.body.input.modelId as number;
  const simulationDatasetId = req.body.input.simulationDatasetId as number;
  const seqIds = (req.body.input.seqIds as number[]).filter((val, index, arr) => arr.indexOf(val) == index); // remove duplicates, if they're even possible

  const seqMetadata = {
    simulationDatasetId
  }

  //  1. Load simulated activities and templates
  const [sequenceTemplates, filteredSimulatedActivitiesBySeqId] = await Promise.all([
    context.sequenceTemplateDataLoader.load({ modelId }),
    context.simulatedActivityInstanceBySeqIdBatchLoader.loadMany(seqIds.map(seqId => {
      return { simulationDatasetId, seqId }
    }))
  ]);

  //  2. Determine the language being used (SeqN vs. STOL)
  //        Presently, we assume based on a database constraint, that all templates pulled for a given model/parcel combo have
  //        the same language. While this constraint will remain true its exact enforcement and therefore implementation in SQL
  //        and here may be subject to change.
  if (sequenceTemplates.length === 0) {
    throw new Error(
      `POST /command-expansion/expand-all-sequence-templates: No sequence templates found for modelId=(${modelId}).`,
    );
  }

  // Check that all languages are the same across all templates for this model
  const languages = sequenceTemplates.map(template => template.language).reduce((previous, current, __, _) => {
    if (previous.includes(current)) {
      return previous;
    }
    else {
      previous.push(current);
      return previous;
    }
  }, [] as string[])

  if (languages.length > 1) {
    throw new Error(
      `POST /command-expansion/expand-all-sequence-templates: Sequence templates found for modelId=(${modelId}) using different languages (${languages}).`
    )
  }

  // Select the correct seqBuilder based on language
  let seqBuilder: SeqBuilder<string, string>;
  if (languages[0] === SequencingLanguage.STOL) {
    seqBuilder = stolBuilder
  } else if (languages[0] === SequencingLanguage.SEQN) {
    seqBuilder = seqnBuilder
  } else if (languages[0] === SequencingLanguage.TEXT) {
    seqBuilder = concatBuilder
  } else {
    throw new Error(
      `POST /command-expansion/expand-all-sequence-templates: Unsupported sequence language "${languages[0]}"`,
    );
  }

  //  3. Pair seqId/SimulatedActivity lists; aggregate all simulated, filtered, activities
  let seqIdToFilteredActivities: { [seqId: string]: { id: number, startOffset: Temporal.Duration }[] } = {};
  let allFilteredActivities: { [id: number]: SimulatedActivity<Record<string, unknown>, Record<string, unknown>> } = [];

  for (const entry of seqIds.entries()) {
    let index = entry[0]
    let seqId = entry[1]

    // filteredActivities is a list of the SimulatedActivities for the current seqId
    const filteredActivities = filteredSimulatedActivitiesBySeqId[index]
    if (filteredActivities && !(filteredActivities instanceof Error)) {
      // Extract just the id and start offset from each simulated activity
      seqIdToFilteredActivities[seqId] = filteredActivities.map(act => {
        return { id: act.id, startOffset: act.startOffset }
      });

      // Add this simulated activity to allFilteredActivities if it's not already there
      // NOTE: The database schema permits a simulated activity to be associated with multiple seq IDs, even though
      //        there is no way to create that multi-association using the UI. This code will honor the multi-association.
      for (const simulatedActivity of filteredActivities) {
        if (!allFilteredActivities[simulatedActivity.id]) {
          allFilteredActivities[simulatedActivity.id] = simulatedActivity
        }
      }
    }
    else {
      if (!filteredActivities) {
        throw new Error(
          `POST /command-expansion/expand-all-sequence-templates: No activities associated with seqId: ${seqId}.`,
        );
      }
      else {
        throw filteredActivities;
      }
    }
  }

  //  4. Create a list of all activity types that are being used.
  const allActivityTypes: string[] = []
  for (const entry of Object.entries(allFilteredActivities)) {
    const activityTypeName = entry[1].activityTypeName
    if (!allActivityTypes.includes(activityTypeName)) {
      allActivityTypes.push(activityTypeName)
    }
  }

  //  5. Correlate each activity type in use with the compiled template for the given model.
  const activityTypeNameToTemplate: { [name: string]: Mustache } = {}
  for (const sequenceTemplate of sequenceTemplates) {
    let activityTypeName = sequenceTemplate.activity_type;

    // by design, duplicate entries (2 templates for 1 activity type in a given model) are impossible. There is no check for it.
    if (allActivityTypes.includes(activityTypeName)) {
      let definition = sequenceTemplate.template_definition;
      activityTypeNameToTemplate[activityTypeName] = new Mustache(definition);
    }
  }

  //  6. Build ExpandedActivity for each activity, a.k.a., run the template expansion for all activities
  const expandedActivities: {
    [id: number]:
    {
      "status": string,
      "value": ExpandedActivity<string>
    }
  } = {}

  for (const simulatedActivityId of Object.keys(allFilteredActivities).map(Number)) {
    if (allFilteredActivities[simulatedActivityId] && !expandedActivities[simulatedActivityId]) {
      const simulatedActivity = allFilteredActivities[simulatedActivityId];
      if (simulatedActivity === undefined) continue;
      const activityTypeName = simulatedActivity.activityTypeName;
      const currentTemplate = activityTypeNameToTemplate[activityTypeName];

      // If no template for this activity type, just continue
      if (currentTemplate) {
        // NOTE: if I have some gibberish as a variable that's obviously not defined, there will be no error.
        //    i.e. "CMD {{ dsvsdfs }}" expands to "CMD ".
        currentTemplate.setLanguage(languages[0])
        const commandString = currentTemplate.execute(stringifyActivity(simulatedActivity))

        // add to results
        expandedActivities[simulatedActivityId] = {
          value: {
            ...simulatedActivity,
            expansionResult: commandString,
            errors: [] // TODO: pass the errors, once we have the errors, if we even can
          },
          status: "fulfilled" // not sure how failure is gonna work...assuming if the template is bad or something
        }
      }
    }
  }

  // 7. Having expanded each simulated activity, now iterate through each seqId to collect the expanded activities for that seqId
  let expandedSequencesBySeqId: { [seqId: string]: string } = {};
  for (const seqId of Object.keys(seqIdToFilteredActivities)) {
    let filteredActivities = seqIdToFilteredActivities[seqId];
    if (filteredActivities === undefined) continue;
    let sortedActivityInstances = filteredActivities.sort((a, b) => Temporal.Duration.compare(a.startOffset, b.startOffset))
    const sortedSimulatedActivitiesWithCommands: ExpandedActivity<string>[] = sortedActivityInstances.reduce((result: ExpandedActivity<string>[], current) => {
      const expandedActivity = expandedActivities[current.id];
      if (!expandedActivity) {
        // Case: this activity wasn't expanded because we didn't have a template for it
        return result;
      } else {
        result.push(expandedActivity.value);
        return result
      }
    }, [])

    // This is here to easily enable a future feature of allowing the mission to configure their own sequence
    // building. For now, we just use the 'defaultSeqBuilder' until such a feature request is made.
    logger.info(`POST /command-expansion/expand-all-sequence-templates: Building sequence for (${seqId}, dataset ${simulationDatasetId})...`)
    const sequence = seqBuilder(sortedSimulatedActivitiesWithCommands, seqId, seqMetadata, simulationDatasetId);
    logger.info(`POST /command-expansion/expand-all-sequence-templates: Sequence completed for (${seqId}, dataset ${simulationDatasetId}).`)

    expandedSequencesBySeqId[seqId] = sequence;
    let rows: any[] = [];
    try {
      rows = await db.query(
        `
        insert into sequencing.expanded_templates (simulation_dataset_id, seq_id, expanded_template)
          values ($1, $2, $3)
          returning id
    `,
        [simulationDatasetId, seqId, sequence],
      ).then(result => result.rows);
    }
    catch (e) {
      if (e instanceof Error) {
        throw new Error(
          `POST /command-expansion/expand-all-sequence-templates: Database insertion failed with "${e.message}"`
        )
      }
      else if (e instanceof String) {
        throw new Error(
          `POST /command-expansion/expand-all-sequence-templates: Database insertion failed with "${e}"`
        )
      }
      else {
        throw new Error(
          `POST /command-expansion/expand-all-sequence-templates: Database insertion failed with "${JSON.stringify(e)}"`
        )
      }
    }

    if (rows.length < 1) {
      throw new Error(
        `POST /command-expansion/expand-all-sequence-templates: No expanded sequences (templates) were inserted into the database`,
      );
    }
    const expandedSequenceId = rows[0].id;
    logger.info(
      `POST /command-expansion/expand-all-sequence-templates: Inserted expanded sequence (templates) to the database: id=${expandedSequenceId}`,
    );
  }

  res.status(200).json({
    success: true,
    expandedSequencesBySeqId
  });

  return next();
});
