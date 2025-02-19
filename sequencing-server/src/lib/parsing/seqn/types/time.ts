export type ParsedDoyString = {
  doy: number;
  hour: number;
  min: number;
  ms: number;
  sec: number;
  time: string;
  year: number;
};

export type ParsedDurationString = {
  days: number;
  hours: number;
  isNegative: boolean;
  microseconds: number;
  milliseconds: number;
  minutes: number;
  seconds: number;
  years: number;
};
export type DurationTimeComponents = {
  days: string;
  hours: string;
  isNegative: string;
  microseconds: string;
  milliseconds: string;
  minutes: string;
  seconds: string;
  years: string;
};

export type ParsedYmdString = {
  day: number;
  hour: number;
  min: number;
  month: number;
  ms: number;
  sec: number;
  time: string;
  year: number;
};
