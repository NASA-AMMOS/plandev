import { ParsedDoyString, ParsedDurationString, ParsedYmdString, TimeTypes } from './types/time.js';
import { Temporal } from '@js-temporal/polyfill';
import { SequencingLanguage } from '../enums/language.js';


/////////////// SEQUENCING-SERVER-SPECIFIC HELPERS ///////////////
export function addTime(startTime: string, duration: string, environment: { language: SequencingLanguage }): string {
  let date: Temporal.Instant
  if (environment.language === SequencingLanguage.STOL) {
    date = STOLToInstant(startTime)
  }
  else if (environment.language === SequencingLanguage.TEXT) {
    date = Temporal.Instant.from(TextToISO8601(startTime))
  }
  else {
    date = SeqNToInstant(startTime)
  }

  let dur: Temporal.Duration;
  if (duration.includes(":")) {
    dur = Temporal.Duration.from(AERIEDurationToISO8601(duration))
  }
  else {
    dur = Temporal.Duration.from(duration)
  }
  date = date.add(dur)

  if (environment.language === SequencingLanguage.STOL) {
    return InstanttoSTOL(date)
  }
  else if (environment.language === SequencingLanguage.TEXT) {
    return InstantToText(date)
  }
  else { 
    return InstanttoSeqN(date)
  }
}

export function subtractTime(startTime: string, duration: string, environment: { language: SequencingLanguage }): string {
  let date: Temporal.Instant
  if (environment.language === SequencingLanguage.STOL) {
    date = STOLToInstant(startTime)
  }
  else if (environment.language === SequencingLanguage.TEXT) {
    date = Temporal.Instant.from(TextToISO8601(startTime))
  }
  else {
    date = SeqNToInstant(startTime)
  }

  let dur: Temporal.Duration;
  if (duration.includes(":")) {
    dur = Temporal.Duration.from(AERIEDurationToISO8601(duration))
  }
  else {
    dur = Temporal.Duration.from(duration)
  }
  date = date.subtract(dur)

  if (environment.language === SequencingLanguage.STOL) {
    return InstanttoSTOL(date)
  }
  else if (environment.language === SequencingLanguage.TEXT) {
    return InstantToText(date)
  }
  else { // Text and SeqN are handled the same.
    return InstanttoSeqN(date)
  }

}

// TIME PARSING
export function SeqNToInstant(date: string): Temporal.Instant {
  return Temporal.Instant.from(convertDoyToYmd(date))
}

export function STOLToInstant(date: string): Temporal.Instant {
  return Temporal.Instant.from(convertDoyToYmd(date))
}

export function TextToISO8601(date: string): string {
  // https://stackoverflow.com/questions/881085/count-the-number-of-occurrences-of-a-character-in-a-string-in-javascript
  if ((date.match(/-/g) || []).length === 2) { // YYYY-MM-DD string
    const result = new Date(date).toISOString()
    return result + (result.includes("Z") ? "" : "Z")
  }
  else { // doy string
    const result = new Date(convertDoyToYmd(date)).toISOString()
    console.log('result doy', result)
    return result + (result.includes("Z") ? "" : "Z")
  }
}

function checkNumDurationComponents(split: string[]): split is [string, string, string] {
  return split.length === 3;
}

export function AERIEDurationToISO8601(duration: string): string {
  // HHHHHH...:MM:SS.mmmuuu -> PHHMMSS.mmmuuuS
  duration = duration.replace("Z", "");
  let split = duration.split(":")
  let isNegative = duration.includes("-")

  if (!checkNumDurationComponents(split)) { // required so editor wouldn't flag a type error
    throw new Error(`Invalid duration string: ${duration}`);
  }

  let hours = parseInt(split[0].replace("-", ""))
  let minutes = parseInt(split[1])
  let split2 = (split[2]).split(".")
  let seconds = parseInt(split2[0] as string)
  if (split2.length > 1) {
    let microseconds = parseInt(split2[1]?.padEnd(6, "0") as string)
    let microsecondsString = `${microseconds}`.padStart(6, "0")
    return `${isNegative ? "-" : ""}PT${hours > 0 ? `${hours}H` : ""}${minutes > 0 ? `${minutes}M` : ""}${seconds > 0 && microseconds > 0 ? `${seconds}.${microsecondsString}S` : (seconds > 0) ? `${seconds}$` : (microseconds > 0) ? `0.${microsecondsString}S` : ""}`
  }
  else {
    return `${isNegative ? "-" : ""}PT${hours > 0 ? `${hours}H` : ""}${minutes > 0 ? `${minutes}M` : ""}${seconds > 0 ? `${seconds}S` : ""}`
  }
}

// CONVERSION BACK TO STRINGS
export function InstanttoSTOL(date: Temporal.Instant): string {
  const stringFormat = date.toString()

  // change to DOY
  let split = stringFormat.split("T")
  let day = new Date(split[0]!)
  let doy = getDoy(day)
  let time = split[1]!

  // extract decimal seconds, if any, and pad by length (ms -> 3 automatically, us -> 6 automatically)
  if (!time.includes(".")) {
    return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}/${time}`
  }
  else {
    const secondSplit = time.split(".")
    const hms = secondSplit[0]
    const decimal = (secondSplit[1]!).replace("Z", "")

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

export function InstanttoSeqN(date: Temporal.Instant): string { // presently, cannot extract fields from Temporal by saying obj.days or anything like that
  const stringFormat = date.toString()

  // change to DOY
  let split = stringFormat.split("T")
  let day = new Date(split[0]!)
  let doy = getDoy(day)
  let time = split[1]!

  // extract decimal seconds, if any, and pad by length (ms -> 3 automatically, us -> 6 automatically)
  if (!time.includes(".")) {
    return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}T${time.replace("Z", "")}`
  }
  else {
    const secondSplit = time.split(".")
    const hms = secondSplit[0]
    const decimal = (secondSplit[1] ?? "000Z").replace("Z", "")

    // seqN doesn't include "Z" in its strings.
    if (decimal.length <= 3) {
      return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}T${hms}.${decimal.padEnd(3, '0')}`
    }
    else {
      return `${day.getUTCFullYear()}-${new String(doy).padStart(3, '0')}T${hms}.${decimal.padEnd(6, '0')}`
    }
  }
}

export function InstantToText(date: Temporal.Instant): string {
  const result = date.toString();
  return result + (result.includes("Z") ? "" : "Z");
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
        `${date.getUTCMonth() + 1}`.padStart(2, '0'),
        `${date.getUTCDate()}`.padStart(2, '0'),
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

    if (year === undefined) {
      throw new Error(`Could not parse doy string because it's missing the year: ${year}`);
    }

    const partialReturn = {
      hour: parseInt(hour),
      min: parseInt(min),
      ms: parseFloat((parseFloat(dec) * msPerSecond).toFixed(numDecimals)),
      sec: parseInt(sec),
      time: time,
      year: parseInt(year as string),
    };

    if (doy !== undefined) {
      return {
        ...partialReturn,
        doy: parseInt(doy),
      };
    }

    return {
      ...partialReturn,
      day: parseInt(day!),
      month: parseInt(month!),
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
