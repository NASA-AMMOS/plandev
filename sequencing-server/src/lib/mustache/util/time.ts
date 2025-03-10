// TODO: Ensure absolute and relative times resolve correctly. Ask about this later.

import { padStart } from 'lodash-es';
import { ParsedDoyString, ParsedDurationString, ParsedYmdString, TimeTypes } from './types/time.js';
import { Temporal } from '@js-temporal/polyfill';


/////////////// SEQUENCING-SERVER-SPECIFIC HELPERS ///////////////
export function addTime(startTime: string, duration: string, environment: { language: string }): string {
  let date: Temporal.Instant
  if (environment.language === "STOL") {
    date = STOLToISO8061(startTime)
  }
  else {
    date = SeqNToISO8061(startTime)
  }

  let dur: Temporal.Duration;
  if (duration.includes(":")) {
    dur = Temporal.Duration.from(AERIEDurationToISO8061(duration))
  }
  else {
    dur = Temporal.Duration.from(duration)
  }
  date = date.add(dur)

  if (environment.language === "STOL") {
    return ISO8061toSTOL(date)
  }
  else {
    return ISO8061toSeqN(date)
  }
}

export function subtractTime(startTime: string, duration: string, environment: { language: string }): string {
  let date: Temporal.Instant
  if (environment.language === "STOL") {
    date = STOLToISO8061(startTime)
  }
  else {
    date = SeqNToISO8061(startTime)
  }

  let dur: Temporal.Duration;
  if (duration.includes(":")) {
    dur = Temporal.Duration.from(AERIEDurationToISO8061(duration))
  }
  else {
    dur = Temporal.Duration.from(duration)
  }
  date = date.subtract(dur)

  if (environment.language === "STOL") {
    return ISO8061toSTOL(date)
  }
  else {
    return ISO8061toSeqN(date)
  }

}

// TIME PARSING
export function SeqNToISO8061(date: string): Temporal.Instant {
  return Temporal.Instant.from(convertDoyToYmd(date))
}

export function STOLToISO8061(date: string): Temporal.Instant {
  return Temporal.Instant.from(convertDoyToYmd(date))
}

export function AERIEDurationToISO8061(duration: string): string {
  // HHHHHH...:MM:SS.mmmuuu -> PHHMMSS.mmmuuuS
  let split = duration.split(":")
  let hours = parseInt(split[0])//.padStart(2, '0')
  let minutes = parseInt(split[1])//.padStart(2, '0')
  let split2 = split[2].split(".")
  let seconds = parseInt(split2[0])//.padStart(2, '0')
  if (split2.length > 1) {
    let microseconds = parseInt(split2[1])//.padEnd(6, '0')

    return `PT${hours > 0 ? `${hours}H` : ""}${minutes > 0 ? `${minutes}M` : ""}${seconds > 0 && microseconds > 0 ? `${seconds}.${microseconds}S` : (seconds > 0) ? `${seconds}$` : (microseconds > 0) ? `0.${microseconds}S` : ""}`
  }
  else {
    return `PT${hours > 0 ? `${hours}H` : ""}${minutes > 0 ? `${minutes}M` : ""}${seconds > 0 ? `${seconds}S` : ""}`
  }
}

// CONVERSION BACK TO STRINGS
export function ISO8061toSTOL(date: Temporal.Instant): string {
  const stringFormat = date.toString()

  // change to DOY
  let split = stringFormat.split("T")
  let day = new Date(split[0])
  let doy = getDoy(day)
  let time = split[1]

  // extract decimal seconds, if any, and pad by length (ms -> 3 automatically, us -> 6 automatically)
  if (!time.includes(".")) {
    return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}/${time}`
  }
  else {
    const secondSplit = time.split(".")
    const hms = secondSplit[0]
    const decimal = secondSplit[1].replace("Z", "")

    // if the Z is present at the end; it may not be and we don't want to extraneously add it
    const zString = time.includes("Z") ? "Z" : ""
    if (decimal.length <= 3) {
      return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}/${hms}.${decimal.padEnd(3, '0')}${zString}`
    }
    else {
      return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}/${hms}.${decimal.padEnd(6, '0')}${zString}`
    }
  }

}

export function ISO8061toSeqN(date: Temporal.Instant): string { // presently, cannot extract fields from Temporal by saying obj.days or anything like that
  const stringFormat = date.toString()

  // change to DOY
  let split = stringFormat.split("T")
  let day = new Date(split[0])
  let doy = getDoy(day)
  let time = split[1]

  // extract decimal seconds, if any, and pad by length (ms -> 3 automatically, us -> 6 automatically)
  if (!time.includes(".")) {
    return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}T${time}`
  }
  else {
    const secondSplit = time.split(".")
    const hms = secondSplit[0]
    const decimal = secondSplit[1].replace("Z", "")

    // if the Z is present at the end; it may not be and we don't want to extraneously add it
    const zString = time.includes("Z") ? "Z" : ""
    if (decimal.length <= 3) {
      return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}T${hms}.${decimal.padEnd(3, '0')}${zString}`
    }
    else {
      return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}T${hms}.${decimal.padEnd(6, '0')}${zString}`
    }
  }
}


/////////////// AERIE-UI HELPERS ///////////////
const ABSOLUTE_TIME = /^(\d{4})-(\d{3})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?$/;
const RELATIVE_TIME =
  /^(?<doy>([0-9]{3}))?(T)?(?<hr>([0-9]{2})):(?<mins>([0-9]{2})):(?<secs>[0-9]{2})?(\.)?(?<ms>([0-9]+))?$/;
const RELATIVE_SIMPLE = /(\d+)(\.[0-9]+)?$/;
const EPOCH_TIME =
  /^((?<sign>[+-]?))(?<doy>([0-9]{3}))?(T)?(?<hr>([0-9]{2})):(?<mins>([0-9]{2})):(?<secs>[0-9]{2})?(\.)?(?<ms>([0-9]+))?$/;
const EPOCH_SIMPLE = /(^[+-]?)(\d+)(\.[0-9]+)?$/;

/**
 * Converts a DOY string (YYYY-DDDDTHH:mm:ss) into a YYYY-MM-DDTHH:mm:ss formatted time string
 */
export function convertDoyToYmd(doyString: string, includeMsecs = true): string {
  const parsedDoy: ParsedDoyString = parseDoyOrYmdTime(doyString) as ParsedDoyString;

  if (parsedDoy !== null) {
    if (parsedDoy.doy !== undefined) {
      const date = new Date(parsedDoy.year, 0, parsedDoy.doy);
      const ymdString = `${[
        date.getFullYear(),
        padStart(`${date.getUTCMonth() + 1}`, 2, '0'),
        padStart(`${date.getUTCDate()}`, 2, '0'),
      ].join('-')}T${parsedDoy.time}`;
      if (includeMsecs) {
        return `${ymdString}${ymdString.charAt(ymdString.length - 1) !== "Z" ? "Z" : ""}`;
      }
      const replaced = ymdString.replace(/(\.\d+)/, '')
      return `${replaced}${replaced.charAt(replaced.length - 1) !== "Z" ? "Z" : ""}`;
    } else {
      // doyString is already in ymd format
      //    just in case - correct and "/" in lieu of a T, i.e. 2025-001/time vs 2025-001Ttime
      return `${doyString.replace("/", "T")}${doyString.charAt(doyString.length - 1) !== "Z" ? "Z" : ""}`;
    }
  }

  throw Error(`Given date: ${doyString} is an invalid DOY (or YMD) string.`)
}

/**
 * Get the day-of-year for a given date.
 * @example getDoy(new Date('1/3/2019')) -> 3
 * @see https://stackoverflow.com/a/8619946
 */
export function getDoy(date: Date): number {
  const start = Date.UTC(date.getUTCFullYear(), 0, 0);
  const diff = date.getTime() - start;
  const oneDay = 8.64e7; // Number of milliseconds in a day.
  return Math.floor(diff / oneDay);
}

/**
 * Parses a date string (YYYY-MM-DDTHH:mm:ss) or DOY string (YYYY-DDDDTHH:mm:ss) into its separate components
 */
function parseDoyOrYmdTime(
  dateString: string,
  numDecimals = 6,
): null | ParsedDoyString | ParsedYmdString | ParsedDurationString {
  const matches = (dateString ?? '').match(
    new RegExp(
      `^(?<year>\\d{4})-(?:(?<month>(?:[0]?[0-9])|(?:[1][0-2]))-(?<day>(?:[0-2]?[0-9])|(?:[3][0-1]))|(?<doy>\\d{1,3}))(?:(T|\/)(?<time>(?<hour>[0-9]|[0-2][0-9])(?::(?<min>[0-9]|(?:[0-5][0-9])))?(?::(?<sec>[0-9]|(?:[0-5][0-9]))(?<dec>\\.\\d{1,${numDecimals}})?)?)?)?(Z)?$`,
      'i',
    ),
  );
  if (matches) {
    const msPerSecond = 1000;

    const { groups: { year, month, day, doy, time = '00:00:00', hour = '0', min = '0', sec = '0', dec = '.0' } = {} } =
      matches;

    const partialReturn = {
      hour: parseInt(hour),
      min: parseInt(min),
      ms: parseFloat((parseFloat(dec) * msPerSecond).toFixed(numDecimals)),
      sec: parseInt(sec),
      time: time,
      year: parseInt(year),
    };

    if (doy !== undefined) {
      return {
        ...partialReturn,
        doy: parseInt(doy),
      };
    }

    return {
      ...partialReturn,
      day: parseInt(day),
      month: parseInt(month),
    };
  }

  const doyDuration = parseDOYDurationTime(dateString);
  if (doyDuration) {
    return doyDuration;
  }

  return null;
}

/**
 * Parses a duration string (DOYTHH:mm:ss.ms) into its separate components
 */
function parseDOYDurationTime(doyTime: string): ParsedDurationString | null {
  const isEpoch = validateTime(doyTime, TimeTypes.EPOCH);
  const matches = isEpoch ? EPOCH_TIME.exec(doyTime) : RELATIVE_TIME.exec(doyTime);
  if (matches !== null) {
    if (matches) {
      const { groups: { sign = '', doy = '0', hr = '0', mins = '0', secs = '0', ms = '0' } = {} } = matches;

      const hoursNum = parseInt(hr);
      const minuteNum = parseInt(mins);
      const secondsNum = parseInt(secs);
      const millisecondNum = parseInt(ms);

      return {
        days: doy !== undefined ? parseInt(doy) : 0,
        hours: hoursNum,
        isNegative: sign !== '' && sign !== '+',
        microseconds: 0,
        milliseconds: millisecondNum,
        minutes: minuteNum,
        seconds: secondsNum,
        years: 0,
      };
    }
  }
  return null;
}

/**
 * Validates a time string based on the specified type.
 * @param {string} time - The time string to validate.
 * @param {TimeTypes} type - The type of time to validate against.
 * @returns {boolean} - True if the time string is valid, false otherwise.
 * @example
 * validateTime('2022-012T12:34:56.789', TimeTypes.ABSOLUTE); // true
 */
function validateTime(time: string, type: TimeTypes): boolean {
  switch (type) {
    case TimeTypes.ABSOLUTE:
      return ABSOLUTE_TIME.exec(time) !== null;
    case TimeTypes.EPOCH:
      return EPOCH_TIME.exec(time) !== null;
    case TimeTypes.RELATIVE:
      return RELATIVE_TIME.exec(time) !== null;
    case TimeTypes.EPOCH_SIMPLE:
      return EPOCH_SIMPLE.exec(time) !== null;
    case TimeTypes.RELATIVE_SIMPLE:
      return RELATIVE_SIMPLE.exec(time) !== null;
    default:
      return false;
  }
}