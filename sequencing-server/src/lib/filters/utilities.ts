import type { ActivityLayerDynamicFilter, ActivityLayerFilter, ActivityType } from "./types";
import type { ActivityLayerFilterField } from "./enums";
import type { SimulatedActivity } from "../batchLoaders/simulatedActivityBatchLoader";

export function applyActivityLayerFilter(
    filter: ActivityLayerFilter | undefined,
    simulatedActivities: SimulatedActivity<Record<string, unknown>, Record<string, unknown>>[],
    timeRangeStart: Temporal.Instant,
    timeRangeEnd: Temporal.Instant
): SimulatedActivity<Record<string, unknown>, Record<string, unknown>>[] {
    if (
        !filter ||
        (!filter.dynamic_type_filters?.length &&
            !filter.other_filters?.length &&
            !filter.static_types?.length &&
            (!filter.type_subfilters || !Object.keys(filter.type_subfilters).length))
    ) {
        return simulatedActivities;
    }

    const staticTypeMap: Record<string, boolean> = (filter.static_types || []).reduce(
        (acc: Record<string, boolean>, cur: string) => {
            acc[cur] = true;
            return acc;
        },
        {},
    );

    return simulatedActivities.filter(simAct => {
        return applyFiltersToDirectiveOrSpan(simAct, filter, staticTypeMap, timeRangeStart, timeRangeEnd);
    });
}

function applyFiltersToDirectiveOrSpan(
    simulatedActivity: SimulatedActivity<Record<string, unknown>, Record<string, unknown>>,
    filter: ActivityLayerFilter,
    staticTypeMap: Record<string, boolean>,
    timeRangeStart: Temporal.Instant,
    timeRangeEnd: Temporal.Instant
) {
    const anyTypeFiltersSpecified = !!(filter.static_types?.length || filter.dynamic_type_filters?.length);
    const anyMainFiltersSpecified = anyTypeFiltersSpecified || !!filter.other_filters?.length;
    let included = !anyMainFiltersSpecified;

    // Check if directive fits in time range
    // Currently, it only disqualifies if the start is out of range. If the end is out of range, it is included.
    if (simulatedActivity.endTime) {
        if (simulatedActivity.startTime.epochMicroseconds < timeRangeStart.epochMicroseconds ||
            simulatedActivity.startTime.epochMicroseconds > timeRangeEnd.epochMicroseconds
        ) {
            return false;
        }
    }
    else {
        if (simulatedActivity.startTime.epochMicroseconds < timeRangeStart.epochMicroseconds) {
            return false;
        }
    }

    // Check to see if directive is included in static list
    if (filter.static_types?.length) {
        included = !!staticTypeMap[simulatedActivity.activityTypeName];
    }

    // Check if necessary to see if directive is included in dynamic list
    if ((!filter.static_types?.length || !included) && filter.dynamic_type_filters?.length) {
        included = directiveOrSpanMatchesDynamicFilters(
            simulatedActivity,
            filter.dynamic_type_filters,
        );
    }

    // Apply other filters on top of the types
    if (filter.other_filters?.length) {
        included =
            directiveOrSpanMatchesDynamicFilters(simulatedActivity, filter.other_filters) &&
            (anyTypeFiltersSpecified ? included : true);
    }

    // Apply type specific filters if found and if the type is already included or
    // if no other filters were specified (case where all types are included by default)
    if (
        filter.type_subfilters &&
        filter.type_subfilters[simulatedActivity.activityTypeName] &&
        filter.type_subfilters[simulatedActivity.activityTypeName]?.length
    ) {
        included =
            directiveOrSpanMatchesDynamicFilters(
                simulatedActivity,
                filter.type_subfilters[simulatedActivity.activityTypeName] ?? [], // we know it shouldn't be undefined, though.
            ) && (anyMainFiltersSpecified ? included : true);
    }
    return included;
}

function directiveOrSpanMatchesDynamicFilters(
    simulatedActivity: SimulatedActivity<Record<string, unknown>, Record<string, unknown>>,
    dynamicFilters: ActivityLayerDynamicFilter<typeof ActivityLayerFilterField>[],
): boolean {
    return dynamicFilters.reduce((acc, curr) => {
        let matches = false;
        if (curr.field === 'Type') {
            matches = matchesDynamicFilter(simulatedActivity.activityTypeName, curr.operator, curr.value);
        } else if (curr.field === 'Name') {
            matches = matchesDynamicFilter(simulatedActivity.id, curr.operator, curr.value);
        } else if (curr.field === 'Parameter' && curr.subfield) {
            const subfield = curr.subfield;
            const args = simulatedActivity.attributes.arguments;
            let argument = args[subfield.name];
            matches = matchesDynamicFilter(argument as string, curr.operator, curr.value);
        }
        return acc && matches;
    }, true);
}


export function matchesDynamicFilter(
    rawItemValue: ActivityLayerDynamicFilter<ActivityLayerFilterField>['value'], // the actual value
    operator: ActivityLayerDynamicFilter<ActivityLayerFilterField>['operator'],
    rawFilterValue: ActivityLayerDynamicFilter<ActivityLayerFilterField>['value'], // the value(s) we're comparing against
) {
    const itemValue = lowercase(rawItemValue);
    if (itemValue === undefined) {
        console.log("UNDEFINED ARGUMENT")
        return false;
    }
    const filterValue = lowercase(rawFilterValue);
    switch (operator) {
        case 'equals':
            return itemValue === filterValue;
        case 'does_not_equal':
            return itemValue !== filterValue;
        case 'includes':
            if (typeof filterValue === 'string' && typeof itemValue === 'string') {
                if (filterValue === '') {
                    return false;
                }
                return itemValue.indexOf(filterValue) > -1;
            } else if (Array.isArray(filterValue)) {
                return !!(Array.isArray(itemValue) ? itemValue : [itemValue]).find(
                    item => (filterValue as (typeof itemValue)[]).indexOf(item) > -1,
                );
            }
            return false;
        case 'does_not_include':
            if (typeof filterValue === 'string' && typeof itemValue === 'string') {
                if (filterValue === '') {
                    return true;
                }
                return itemValue.indexOf(filterValue) < 0;
            } else if (Array.isArray(filterValue)) {
                return !(Array.isArray(itemValue) ? itemValue : [itemValue]).find(
                    item => (filterValue as (typeof itemValue)[]).indexOf(item) > -1,
                );
            }
            return false;
        case 'is_greater_than':
            return itemValue > filterValue;
        case 'is_less_than':
            return itemValue < filterValue;
        case 'is_within':
            if (
                Array.isArray(filterValue) &&
                filterValue.length === 2 &&
                typeof filterValue[0] === 'number' &&
                typeof filterValue[1] === 'number'
            ) {
                // TODO should upper bound be inclusive or exclusive?
                return itemValue >= filterValue[0] && itemValue <= filterValue[1];
            }
            return false;
        case 'is_not_within':
            if (
                Array.isArray(filterValue) &&
                filterValue.length === 2 &&
                typeof filterValue[0] === 'number' &&
                typeof filterValue[1] === 'number'
            ) {
                // TODO should upper bound be inclusive or exclusive?
                return itemValue < filterValue[0] || itemValue > filterValue[1];
            }
            return false;
        default:
            return false;
    }
}

export function getMatchingTypesForActivityLayerFilter(filter: ActivityLayerFilter | undefined, types: ActivityType[]) {
    if (
        !filter ||
        (!filter.dynamic_type_filters?.length &&
            !filter.other_filters?.length &&
            !filter.static_types?.length &&
            (!filter.type_subfilters || !Object.keys(filter.type_subfilters).length))
    ) {
        return types;
    }

    const staticTypeMap: Record<string, boolean> = (filter.static_types || []).reduce(
        (acc: Record<string, boolean>, cur: string) => {
            acc[cur] = true;
            return acc;
        },
        {},
    );

    const anyTypeFiltersSpecified = !!(filter.static_types?.length || filter.dynamic_type_filters?.length);

    return types.filter(type => {
        let included = !anyTypeFiltersSpecified;

        // Check to see if type is included in static list
        if (filter.static_types?.length) {
            included = !!staticTypeMap[type.name];
        }

        // Check if necessary to see if type is included in dynamic list
        if ((!filter.static_types?.length || !included) && filter.dynamic_type_filters?.length) {
            included = typeMatchesDynamicFilters(type, filter.dynamic_type_filters);
        }
        return included;
    });
}

function typeMatchesDynamicFilters(
    type: ActivityType,
    dynamicFilters: ActivityLayerDynamicFilter<typeof ActivityLayerFilterField>[],
): boolean {
    return dynamicFilters.reduce((acc, curr) => {
        let matches = false;
        if (curr.field === 'Type') {
            matches = matchesDynamicFilter(type.name, curr.operator, curr.value);
        } else if (curr.field === 'Subsystem') {
            matches = matchesDynamicFilter(type.subsystem_tag?.id ?? -1, curr.operator, curr.value);
        }
        return acc && matches;
    }, true);
}

// from utilities/generic.ts
export function lowercase(value: any) {
    return typeof value === 'string' ? value.toLowerCase() : value;
}
