// Only using: getBalancedDuration, getDurationTimeComponents, parseDurationString, validateTime

import parseInterval from 'postgres-interval';
import { TimeTypes } from './enums/time.js';
import type { DurationTimeComponents, ParsedDoyString, ParsedDurationString, ParsedYmdString } from './types/time.js';

const ABSOLUTE_TIME = /^(\d{4})-(\d{3})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{3}))?$/;
const RELATIVE_TIME =
  /^(?<doy>([0-9]{3}))?(T)?(?<hr>([0-9]{2})):(?<mins>([0-9]{2})):(?<secs>[0-9]{2})?(\.)?(?<ms>([0-9]+))?$/;
const RELATIVE_SIMPLE = /(\d+)(\.[0-9]+)?$/;
const EPOCH_TIME =
  /^((?<sign>[+-]?))(?<doy>([0-9]{3}))?(T)?(?<hr>([0-9]{2})):(?<mins>([0-9]{2})):(?<secs>[0-9]{2})?(\.)?(?<ms>([0-9]+))?$/;
const EPOCH_SIMPLE = /(^[+-]?)(\d+)(\.[0-9]+)?$/;

/**
 * Validates a time string based on the specified type.
 * @param {string} time - The time string to validate.
 * @param {TimeTypes} type - The type of time to validate against.
 * @returns {boolean} - True if the time string is valid, false otherwise.
 * @example
 * validateTime('2022-012T12:34:56.789', TimeTypes.ABSOLUTE); // true
 */
export function validateTime(time: string, type: TimeTypes): boolean {
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

/**
 * Determines if the given time string is a max time based on the specified time type.
 * @param {string} time - The time string to check.
 * @param {TimeTypes} type - The time type to check against.
 * @returns {boolean} - True if the time string is a max time, false otherwise.
 * @example
 * isTimeMax('2099-365T23:59:59.999', TimeTypes.ABSOLUTE); // false
 */
export function isTimeMax(time: string, type: TimeTypes): boolean {
  switch (type) {
    case TimeTypes.ABSOLUTE: {
      const year = (parseDoyOrYmdTime(getDoyTime(new Date(getUnixEpochTime(time)))) as ParsedDoyString)?.year;
      return year ? year > 9999 : true;
    }
    case TimeTypes.EPOCH:
    case TimeTypes.RELATIVE: {
      const duration = parseDurationString(time);
      const originalYear = parseInt(convertDurationToDoy(duration).slice(0, 4));
      const year = (
        parseDoyOrYmdTime(getDoyTime(new Date(getUnixEpochTime(convertDurationToDoy(duration))))) as ParsedDoyString
      )?.year;
      return originalYear !== year;
    }
    default:
      return false;
  }
}

/**
 * Determines if the given time string is balanced based on the specified time type.
 * @param {string} time - The time string to check.
 * @param {TimeTypes} type - The time type to check against.
 * @returns {boolean} - True if the time string is balanced, false otherwise.
 * @example
 * isTimeBalanced('2022-01-01T00:00:00.000', TimeTypes.ABSOLUTE); // true
 * isTimeBalanced('50000d', TimeTypes.RELATIVE); // false
 */
export function isTimeBalanced(time: string, type: TimeTypes): boolean {
  switch (type) {
    case TimeTypes.ABSOLUTE: {
      const balancedTime = parseDoyOrYmdTime(getDoyTime(new Date(getUnixEpochTime(time)))) as ParsedDoyString;
      const originalTime = parseDoyOrYmdTime(time) as ParsedDoyString;
      if (balancedTime === null || originalTime === null) {
        return false;
      }
      return originalTime.year === balancedTime.year;
    }
    case TimeTypes.EPOCH:
    case TimeTypes.RELATIVE: {
      const originalTime = parseDurationString(time);
      const balancedTime = parseDurationString(getBalancedDuration(time));

      if (balancedTime === null || originalTime === null) {
        return false;
      }
      return (
        balancedTime.days === originalTime.days &&
        balancedTime.hours === originalTime.hours &&
        balancedTime.minutes === originalTime.minutes &&
        balancedTime.seconds === originalTime.seconds &&
        balancedTime.milliseconds === originalTime.milliseconds
      );
    }
    default:
      return false;
  }
}

/**
 * Parse a duration string into a parsed duration object.
 * If no unit is specified, it defaults to microseconds.
 *
 * @example
 * parseDurationString('1h 30m');
 * // => {
 * // =>   days: 0,
 * // =>   hours: 1,
 * // =>   isNegative: false,
 * // =>   microseconds: 0,
 * // =>   milliseconds: 0,
 * // =>   minutes: 30,
 * // =>   seconds: 0,
 * // =>   years: 0,
 * // => }
 * @example
 * parseDurationString('-002T00:45:00.010')
 * // => {
 * // =>   days: 2,
 * // =>   hours: 0,
 * // =>   isNegative: true,
 * // =>   microseconds: 0,
 * // =>   milliseconds: 10,
 * // =>   minutes: 45,
 * // =>   seconds: 0,
 * // =>   years: 0,
 * // => }
 * @example
 * parseDurationString('90')
 * // => {
 * // =>   minutes: 0,
 * // =>   seconds: 0,
 * // =>   microseconds: 90,
 * // =>   milliseconds: 0,
 * // =>   days: 0,
 * // =>   hours: 0,
 * // =>   isNegative: false,
 * // =>   years: 0,
 * // => }
 * @example
 * parseDurationString('-123.456s', 'microseconds')
 * // => {
 * // =>   microseconds: 0,
 * // =>   milliseconds: 456,
 * // =>   seconds: -123,
 * // =>   minutes: 0,
 * // =>   hours: 0,
 * // =>   days: 0,
 * // =>   isNegative: true,
 * // =>   years: 0,
 * // => }
 *
 * @param {string} durationString - The duration string to parse.
 * @param {'years' | 'days' | 'hours' | 'minutes' | 'seconds' | 'milliseconds' | 'microseconds'} units - The units to parse the duration string in.
 * @return {ParsedDurationString} The parsed duration object.
 * @throws {Error} If the duration string is invalid.
 */
export function parseDurationString(
  durationString: string,
  units: 'days' | 'hours' | 'minutes' | 'seconds' | 'milliseconds' | 'microseconds' = 'microseconds',
): ParsedDurationString | never {
  const validNegationRegex = `((?<isNegative>-))?`;
  const validDurationValueRegex = `([+-]?)(\\d+)(\\.\\d+)?`;
  const validYearsDurationRegex = `(?:\\s*(?<years>${validDurationValueRegex})y)`;
  const validDaysDurationRegex = `(?:\\s*(?<days>${validDurationValueRegex})d)`;
  const validHoursDurationRegex = `(?:\\s*(?<hours>${validDurationValueRegex})h)`;
  const validMinutesDurationRegex = `(?:\\s*(?<minutes>${validDurationValueRegex})m(?!s))`;
  const validSecondsDurationRegex = `(?:\\s*(?<seconds>${validDurationValueRegex})s)`;
  const validMillisecondsDurationRegex = `(?:\\s*(?<milliseconds>${validDurationValueRegex})ms)`;
  const validMicrosecondsDurationRegex = `(?:\\s*(?<microseconds>${validDurationValueRegex})us)`;

  const fullValidDurationRegex = new RegExp(
    `^${validNegationRegex}${validYearsDurationRegex}?${validDaysDurationRegex}?${validHoursDurationRegex}?${validMinutesDurationRegex}?${validSecondsDurationRegex}?${validMillisecondsDurationRegex}?${validMicrosecondsDurationRegex}?$`,
  );

  let matches = durationString.match(fullValidDurationRegex);

  if (matches !== null) {
    const {
      groups: {
        isNegative = '',
        years = '0',
        days = '0',
        hours = '0',
        minutes = '0',
        seconds = '0',
        milliseconds = '0',
        microseconds = '0',
      } = {},
    } = matches;

    return {
      days: parseFloat(days),
      hours: parseFloat(hours),
      isNegative: !!isNegative,
      microseconds: parseFloat(microseconds),
      milliseconds: parseFloat(milliseconds),
      minutes: parseFloat(minutes),
      seconds: parseFloat(seconds),
      years: parseFloat(years),
    };
  }

  const durationTime = parseDoyOrYmdTime(durationString) as ParsedDurationString;
  if (durationTime) {
    return durationTime;
  }

  matches = new RegExp(`^(?<sign>([+-]?))(?<int>(\\d+))(?<decimal>\\.(\\d+))?$`).exec(durationString);
  if (matches !== null) {
    const { groups: { sign = '', int = '0', decimal = '0' } = {} } = matches;
    let microsecond = 0;
    let millisecond = 0;
    let second = 0;
    let minute = 0;
    let hour = 0;
    let day = 0;
    let year = 0;

    const number = parseInt(int);
    const decimalNum = decimal ? parseFloat(decimal) : 0;

    //shift everthing based on units
    switch (units) {
      case 'microseconds':
        microsecond = number;
        break;
      case 'milliseconds':
        microsecond = decimalNum;
        millisecond = number;
        break;
      case 'seconds':
        millisecond = decimalNum;
        second = number;
        break;
      case 'minutes':
        second = decimalNum;
        minute = number;
        break;
      case 'hours':
        minute = decimalNum;
        hour = number;
        break;
      case 'days':
        hour = decimalNum;
        day = number;
        break;
    }

    // Normalize microseconds
    millisecond += Math.floor(microsecond / 1000000);
    microsecond = microsecond % 1000000;

    // Normalize milliseconds and seconds
    second += Math.floor(millisecond / 1000);
    millisecond = millisecond % 1000;

    // Normalize seconds and minutes
    minute += Math.floor(second / 60);
    second = second % 60;

    // Normalize minutes and hours
    hour += Math.floor(minute / 60);
    minute = minute % 60;

    // Normalize hours and days
    day += Math.floor(hour / 24);
    hour = hour % 24;

    // Normlize days and years
    year += Math.floor(day / 365);
    day = day % 365;

    return {
      days: day,
      hours: hour,
      isNegative: sign !== '' && sign !== '+',
      microseconds: microsecond,
      milliseconds: millisecond,
      minutes: minute,
      seconds: second,
      years: year,
    };
  }

  throw new Error(`Invalid time format: Must be of format:
    1y 3d 2h 24m 35s 18ms 70us,
    [+/-]DOYThh:mm:ss[.sss],
    duration
    `);
}

/**
 * Format a duration object to a day of year string.
 *
 * @example
 * convertDurationToDoy({
 *   years: 0,
 *   days: 1,
 *   hours: 0,
 *   minutes: 45,
 *   seconds: 0,
 *   milliseconds: 10,
 *   microseconds: 0,
 * })
 *
 * result: '1970-1T00:45:00.010'
 *
 * @param {ParsedDurationString} duration - The duration object to format.
 * @returns {string} - The formatted day of year string.
 */
function convertDurationToDoy(duration: ParsedDurationString): string {
  const years = duration.years === 0 ? '1970' : String(duration.years).padStart(4, '0');
  const day = Math.max(1, Math.floor(duration.days));
  const hours = String(duration.hours).padStart(2, '0');
  const minutes = String(duration.minutes).padStart(2, '0');
  const seconds = String(duration.seconds).padStart(2, '0');
  const milliseconds = String(duration.milliseconds * 1000).padStart(3, '0');

  return `${years}-${day.toString().padStart(3, '0')}T${hours}:${minutes}:${seconds}.${milliseconds}`;
}

/**
 * Gets the balanced duration based on the given time string.
 *
 * @example
 * getBalancedDuration('-002T00:60:00.010')
 * // => '-002T01:00:00.010'
 *
 * @param {string} time - The time string to calculate the balanced duration from.
 * @returns {string} The balanced duration string.
 */
export function getBalancedDuration(time: string): string {
  const duration = parseDurationString(time, 'seconds');
  const balancedTime = getDoyTime(new Date(getUnixEpochTime(convertDurationToDoy(duration))));
  const parsedBalancedTime = parseDoyOrYmdTime(balancedTime) as ParsedDoyString;
  const shouldIncludeDay = duration.days > 0 || parsedBalancedTime.doy > 1;

  const sign = duration.isNegative ? '-' : '';
  const day = shouldIncludeDay
    ? `${String(parsedBalancedTime.doy - (duration.days > 0 ? 0 : 1)).padStart(3, '0')}T`
    : '';
  const hour = String(parsedBalancedTime.hour).padStart(2, '0');
  const minutes = String(parsedBalancedTime.min).padStart(2, '0');
  const seconds = String(parsedBalancedTime.sec).padStart(2, '0');
  const milliseconds = String(parsedBalancedTime.ms).padStart(3, '0');
  return `${sign}${day}${hour}:${minutes}:${seconds}.${milliseconds}`;
}

function addUnit(value: number, unit: string, isNegative: boolean) {
  return `${isNegative ? '-' : ''}${value} ${value !== 1 ? `${unit}s` : unit}`;
}

/**
 * Converts a DOY string (YYYY-DDDDTHH:mm:ss) into a YYYY-MM-DDTHH:mm:ss formatted time string
 */
export function convertDoyToYmd(doyString: string, includeMsecs = true): string | null {
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
        return `${ymdString}Z`;
      }
      return `${ymdString.replace(/(\.\d+)/, '')}Z`;
    } else {
      // doyString is already in ymd format
      return `${doyString}Z`;
    }
  }

  return null;
}

/**
 * Get a Postgres Interval duration given a string duration of the format '2y 318d 6h 16m 19s 200ms 0us'.
 * @example convertDurationStringToInterval('8d 6h 16m') -> 8 days 6 hours 16 minutes
 */
export function convertDurationStringToInterval(durationString: string): string | never {
  const parsedDuration = parseDurationString(convertUsToDurationString(convertDurationStringToUs(durationString)));

  const { isNegative, years, days, hours, minutes, seconds, milliseconds, microseconds } = parsedDuration;

  const yearsString = years ? addUnit(years, 'year', isNegative) : '';
  const daysString = days ? addUnit(days, 'day', isNegative) : '';
  const hoursString = hours ? addUnit(hours, 'hour', isNegative) : '';
  const minutesString = minutes ? addUnit(minutes, 'minute', isNegative) : '';
  const secondsString = seconds ? addUnit(seconds, 'second', isNegative) : '';
  const millisecondsString = milliseconds ? addUnit(milliseconds, 'millisecond', isNegative) : '';
  const microsecondsString = microseconds ? addUnit(microseconds, 'microsecond', isNegative) : '';

  const intervalString = [
    yearsString,
    daysString,
    hoursString,
    minutesString,
    secondsString,
    millisecondsString,
    microsecondsString,
  ]
    .filter(Boolean)
    .join(' ');

  return intervalString ? intervalString : '0';
}

/**
 * Convert a Postgres UTC string to milliseconds, for rendering purposes.
 * @param date The date as a string to convert.
 * @returns The number of milliseconds since epoch start for this date.
 */
export function convertUTCToMs(date: string): number {
  const d = new Date(date);
  return d.getTime();
}

/**
 * Get a number value in microseconds given a string duration of the format '2y 318d 6h 16m 19s 200ms 0us'.
 * @example convertDurationStringToUs('2y 318d 6h 16m 19s 200ms 0us') -> 90577779200000
 * @note inverse of convertUsToDurationString
 */
export function convertDurationStringToUs(durationString: string): number | never {
  const usPerYear = 3.154e13;
  const usPerDay = 8.64e10;
  const usPerHour = 3.6e9;
  const usPerMinute = 6e7;
  const usPerSecond = 1000000;
  const usPerMillisecond = 1000;

  const parsedDuration = parseDurationString(durationString);

  let durationUs = 0;

  const { isNegative, years, days, hours, minutes, seconds, milliseconds, microseconds } = parsedDuration;

  durationUs += years * usPerYear;
  durationUs += days * usPerDay;
  durationUs += hours * usPerHour;
  durationUs += minutes * usPerMinute;
  durationUs += seconds * usPerSecond;
  durationUs += milliseconds * usPerMillisecond;
  durationUs += microseconds;

  return durationUs * (isNegative ? -1 : 1);
}

/**
 * Get a formatted duration string given a duration in microseconds.
 * @example convertUsToDurationString(90577779200000) -> '2y 318d 6h 16m 19s 200ms'
 * @note inverse of convertDurationStringToUs
 */
export function convertUsToDurationString(durationUs: number, includeZeros: boolean = false): string {
  const usPerYear = 3.154e13;
  const usPerDay = 8.64e10;
  const usPerHour = 3.6e9;
  const usPerMinute = 6e7;
  const usPerSecond = 1000000;
  const usPerMillisecond = 1000;

  const hoursPerDay = 24;
  const minutesPerHour = 60;
  const secondsPerMinute = 60;

  const isNegative = durationUs < 0;
  let absoluteDuration = Math.abs(durationUs);

  const years = Math.floor(absoluteDuration / usPerYear);
  absoluteDuration -= years * usPerYear;

  const days = Math.floor(absoluteDuration / usPerDay);
  absoluteDuration -= days * usPerDay;

  const hours = Math.floor(absoluteDuration / usPerHour) % hoursPerDay;
  absoluteDuration -= hours * usPerHour;

  const minutes = Math.floor(absoluteDuration / usPerMinute) % minutesPerHour;
  absoluteDuration -= minutes * usPerMinute;

  const seconds = Math.floor(absoluteDuration / usPerSecond) % secondsPerMinute;
  absoluteDuration -= seconds * usPerSecond;

  const milliseconds = Math.floor(absoluteDuration / usPerMillisecond);
  absoluteDuration -= milliseconds * usPerMillisecond;

  const microseconds = absoluteDuration;

  if (includeZeros) {
    return `${
      isNegative ? '- ' : ''
    }${years}y ${days}d ${hours}h ${minutes}m ${seconds}s ${milliseconds}ms ${microseconds}us`;
  }

  const negativeString = isNegative ? '-' : '';
  const yearsString = years ? `${years}y` : '';
  const daysString = days ? `${days}d` : '';
  const hoursString = hours ? `${hours}h` : '';
  const minutesString = minutes ? `${minutes}m` : '';
  const secondsString = seconds ? `${seconds}s` : '';
  const millisecondsString = milliseconds ? `${milliseconds}ms` : '';
  const microsecondsString = microseconds ? `${microseconds}us` : '';

  return [
    negativeString,
    yearsString,
    daysString,
    hoursString,
    minutesString,
    secondsString,
    millisecondsString,
    microsecondsString,
  ]
    .filter(Boolean)
    .join(' ');
}

/**
 * Get the number of days in a given month (0-11) of a specific year.
 * @example getDaysInMonth(2020, 5) -> 3
 */
export function getDaysInMonth(year: number, month: number): number {
  const lastOfMonth = new Date(Date.UTC(year, month + 1, 0));

  return lastOfMonth.getUTCDate();
}

export function getDaysInYear(year: number): number {
  let daysInYear = 0;
  for (let month: number = 0; month < 12; month++) {
    daysInYear += getDaysInMonth(year, month);
  }

  return daysInYear;
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
 * Get the DOY components for a given JavaScript Date object.
 */
export function getDoyTimeComponents(date: Date) {
  return {
    doy: getDoy(date).toString().padStart(3, '0'),
    hours: date.getUTCHours().toString().padStart(2, '0'),
    mins: date.getUTCMinutes().toString().padStart(2, '0'),
    msecs: date.getUTCMilliseconds().toString().padStart(3, '0'),
    secs: date.getUTCSeconds().toString().padStart(2, '0'),
    year: date.getUTCFullYear().toString(),
  };
}

/**
 * Get the time components for a given duration object.
 * @example getDurationTimeComponents({ years: 2, days: 3, hours: 10, minutes: 30, seconds: 45, milliseconds: 0, microseconds: 0, isNegative: false })
 * -> { days: '003', hours: '10', isNegative: '', microseconds: '', milliseconds: '000', minutes: '30', seconds: '45', years: '0002' }
 */
export function getDurationTimeComponents(duration: ParsedDurationString): DurationTimeComponents {
  return {
    days: duration.days !== 0 ? String(duration.days).padStart(3, '0') : '',
    hours: duration.hours.toString().padStart(2, '0'),
    isNegative: duration.isNegative ? '-' : '',
    microseconds: duration.microseconds !== 0 ? String(duration.microseconds).padStart(3, '0') : '',
    milliseconds: duration.milliseconds !== 0 ? String(duration.milliseconds).padStart(3, '0') : '',
    minutes: duration.minutes.toString().padStart(2, '0'),
    seconds: duration.seconds.toString().padStart(2, '0'),
    years: duration.years.toString().padStart(4, '0'),
  };
}

/**
 * Get a day-of-year timestamp from a given JavaScript Date object.
 * @example getDoyTime(new Date(1577779200000)) -> 2019-365T08:00:00
 * @note inverse of getUnixEpochTime
 * @note milliseconds will be dropped if all 0s
 */
export function getDoyTime(date: Date, includeMsecs = true): string {
  const { doy, hours, mins, msecs, secs, year } = getDoyTimeComponents(date);
  let doyTimestamp = `${year}-${doy}T${hours}:${mins}:${secs}`;

  if (includeMsecs && date.getMilliseconds() > 0) {
    doyTimestamp += `.${msecs}`;
  }

  return doyTimestamp;
}

/**
 * Get a day-of-year timestamp from a given an ISO 8601 start time string, and a Postgres Interval duration.
 */
export function getDoyTimeFromInterval(startTime: string, interval: string, includeMsecs = true): string {
  const startDate = new Date(startTime);
  const parsedInterval = parseInterval(interval);
  const { days, hours, milliseconds, minutes, seconds } = parsedInterval;
  const endDate = new Date(startDate.getTime());
  endDate.setUTCDate(endDate.getUTCDate() + days);
  endDate.setUTCHours(endDate.getUTCHours() + hours);
  endDate.setUTCMinutes(endDate.getUTCMinutes() + minutes);
  endDate.setUTCSeconds(endDate.getUTCSeconds() + seconds);
  endDate.setUTCMilliseconds(endDate.getUTCMilliseconds() + milliseconds);
  return getDoyTime(endDate, includeMsecs);
}

/**
 * Returns a Postgres Interval duration in milliseconds.
 * If duration is null, undefined, or empty string then we just return 0.
 * @note This function assumes 24-hour days.
 */
export function getIntervalInMs(interval: string | null | undefined): number {
  if (interval !== null && interval !== undefined && interval !== '') {
    const parsedInterval = parseInterval(interval);
    const { days, hours, milliseconds, minutes, seconds } = parsedInterval;
    const daysInMs = days * 24 * 60 * 60 * 1000;
    const hoursInMs = hours * 60 * 60 * 1000;
    const minutesInMs = minutes * 60 * 1000;
    const secondsInMs = seconds * 1000;
    return daysInMs + hoursInMs + minutesInMs + secondsInMs + milliseconds;
  }
  return 0;
}

/**
 * Returns a Postgres Interval over the specified range of DOY strings.
 */
export function getIntervalFromDoyRange(startTime: string, endTime: string): string {
  const startTimeMs = getUnixEpochTime(startTime);
  const endTimeMs = getUnixEpochTime(endTime);
  return getIntervalUnixEpochTime(startTimeMs, endTimeMs);
}

/**
 * Returns a Postgres Interval over the specified range of unix epoch UTC times.
 */
export function getIntervalUnixEpochTime(startTimeMs: number, endTimeMs: number): string {
  const differenceMs = endTimeMs - startTimeMs;

  const isNegative = differenceMs < 0;
  const absoluteDifferenceMs = Math.abs(differenceMs);

  const seconds = Math.floor(absoluteDifferenceMs / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remainingSeconds = seconds % 60;
  const remainingMilliseconds = absoluteDifferenceMs % 1000;

  const paddedHours = hours.toString().padStart(2, '0');
  const paddedMinutes = minutes.toString().padStart(2, '0');
  const paddedSeconds = remainingSeconds.toString().padStart(2, '0');
  const paddedMilliseconds = remainingMilliseconds.toString().padStart(3, '0');

  const sign = isNegative ? '-' : '';
  return `${sign}${paddedHours}:${paddedMinutes}:${paddedSeconds}.${paddedMilliseconds}`;
}

/**
 * Get a unix epoch time in milliseconds given a day-of-year timestamp.
 * @example getUnixEpochTime('2019-365T08:00:00.000') -> 1577779200000
 * @note inverse of getDoyTime
 */
export function getUnixEpochTime(doyTimestamp: string): number {
  const re = /(\d{4})-(\d{3})T(\d{2}):(\d{2}):(\d{2})\.?(\d{3})?/;
  const match = re.exec(doyTimestamp);

  if (match) {
    const [, year, doy, hours, mins, secs, msecs = '0'] = match;
    return Date.UTC(parseInt(year ?? "2025"), 0, parseInt(doy ?? "01"), parseInt(hours ?? "00"), parseInt(mins ?? "00"), parseInt(secs ?? "00"), parseInt(msecs ?? "000"));
  }

  return 0;
}

/**
 * Helper to calculate a unix epoch time in UTC from a start time and Postgres interval.
 */
export function getUnixEpochTimeFromInterval(startTime: string, interval: string, includeMsecs = true): number {
  const doyTime = getDoyTimeFromInterval(startTime, interval, includeMsecs);
  return getUnixEpochTime(doyTime);
}

/**
 * Parses a date string (YYYY-MM-DDTHH:mm:ss) or DOY string (YYYY-DDDDTHH:mm:ss) into its separate components
 */
export function parseDoyOrYmdTime(
  dateString: string,
  numDecimals = 6,
): null | ParsedDoyString | ParsedYmdString | ParsedDurationString {
  const matches = (dateString ?? '').match(
    new RegExp(
      `^(?<year>\\d{4})-(?:(?<month>(?:[0]?[0-9])|(?:[1][0-2]))-(?<day>(?:[0-2]?[0-9])|(?:[3][0-1]))|(?<doy>\\d{1,3}))(?:T(?<time>(?<hour>[0-9]|[0-2][0-9])(?::(?<min>[0-9]|(?:[0-5][0-9])))?(?::(?<sec>[0-9]|(?:[0-5][0-9]))(?<dec>\\.\\d{1,${numDecimals}})?)?)?)?$`,
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
      year: parseInt(year ?? "2025"),
    };

    if (doy !== undefined) {
      return {
        ...partialReturn,
        doy: parseInt(doy),
      };
    }

    return {
      ...partialReturn,
      day: parseInt(day ?? "01"),
      month: parseInt(month ?? "01"),
    };
  }

  const doyDuration = parseDOYDurationTime(dateString);
  if (doyDuration) {
    return doyDuration;
  }

  return null;
}

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
 * Returns a string indicating how long ago a date was compared to the given date.
 * Optionally pass in formatAsDateAfterMS to override the default 1 day cut off in MS
 * after which the date will be formatted in a more conventional form.
 * @example getTimeAgo(new Date()) -> 0s
 * @example getTimeAgo(new Date(new Date().getTime() - 1000), new Date(new Date().getTime())) -> 0s
 */
export function getTimeAgo(
  date: Date,
  comparisonDate: Date = new Date(),
  formatAsDateAfterMS: number = 1000 * 60 * 60 * 23,
) {
  const comparisonDateTime = comparisonDate.getTime();
  const diff = comparisonDateTime - date.getTime();
  if (diff < 1000) {
    return 'Now';
  }
  // Format as mm-dd-YYYY if over limit
  if (diff > formatAsDateAfterMS) {
    return date.toISOString().slice(0, 10);
  }
  return `${convertUsToDurationString((comparisonDateTime - date.getTime()) * 1000).split(' ')[0]} ago`;
}

/**
 * Returns a date formatted in ISO string without milliseconds and timezone identifier
 * @example getShortISOForDate(new Date("2023-05-23T00:30:09.597Z")) -> '2023-05-23T00:30:09'
 */
export function getShortISOForDate(date: Date) {
  return date.toISOString().slice(0, 19);
}

export function getShortTimeZoneName() {
  return new Intl.DateTimeFormat('en-US', { timeZoneName: 'short' }).format(new Date());
}

export function getTimeZoneName() {
  // set up formatter
  const formatter = new Intl.DateTimeFormat(undefined, {
    timeZoneName: 'short',
  });
  // run formatter on current date
  const parts = formatter?.formatToParts(Date.now());
  // extract the actual value from the formatter
  const timeZoneName = parts.find(formatted => formatted.type === 'timeZoneName');
  if (timeZoneName) {
    return timeZoneName.value;
  }
  return 'UNK';
}

/**
 * Removes milliseconds from the string if in DOY time format,
 * otherwise returns the original string.
 */
export function removeDateStringMilliseconds(dateString: string): string {
  if (validateTime(dateString, TimeTypes.ABSOLUTE)) {
    return dateString.split('.')[0] ?? dateString;
  }
  return dateString;
}
