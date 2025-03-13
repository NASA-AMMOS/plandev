import type { SimulatedActivity } from "../../batchLoaders/simulatedActivityBatchLoader";

// converts SimulatedActivity into something mustache plays well with
export function stringifyActivity(simulatedActivity: SimulatedActivity): MustacheActivity {
    // https://stackoverflow.com/questions/62905565/typescript-iterate-over-a-record-type-and-return-updated-record
    const updatedArguments: Record<string, string> = Object.entries(simulatedActivity.attributes.arguments).reduce<Record<string, string>>((acc, curr) => {
        const argumentName = curr[0]
        const argumentValue = curr[1]

        acc[argumentName] = String(argumentValue)
        return acc
    }, {});

    const updatedComputed: Record<string, string> | undefined = simulatedActivity.attributes.computed ? Object.entries(simulatedActivity.attributes.computed).reduce<Record<string, string>>((acc, curr) => {
        const argumentName = curr[0]
        const argumentValue = curr[1]

        acc[argumentName] = String(argumentValue)
        return acc
    }, {}) : undefined;

    return {
        ...simulatedActivity,
        duration: simulatedActivity.duration ? simulatedActivity.duration.toString() : null,
        startOffset: simulatedActivity.startOffset.toString(),
        startTime: simulatedActivity.startTime.toString(),
        endTime: simulatedActivity.endTime ? simulatedActivity.endTime.toString() : null,
        attributes: {
            arguments: updatedArguments,
            directiveId: simulatedActivity.attributes.directiveId,
            computed: updatedComputed
        }
    }
}

export type MustacheActivity = {
    id: number;
    simulationDatasetId: number;
    simulationDataset: {
        simulation: {
            planId: number;
        };
    };
    attributes: {
        arguments: Record<string, string>;
        directiveId: number | undefined;
        computed: Record<string, string> | undefined;
    };
    duration: string | null;
    startOffset: string;
    startTime: string;
    endTime: string | null;
    activityTypeName: string;
};
