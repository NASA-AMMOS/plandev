import type { ValidationResult } from './form';

export type PluginCode = {
  getPlugin: () => Promise<Plugins>;
};

export type PluginTime = {
  format: (date: Date) => string | null;
  formatShort: (date: Date) => string | null;
  formatString: string;
  formatTick: (date: Date, durationMs: number, tickCount: number) => string | null;
  label: string;
  parse: (string: string) => Date | null;
  validate: (string: string) => Promise<ValidationResult>;
};

type Optional<T, K extends keyof T> = Pick<Partial<T>, K> & Omit<T, K>;

export type Plugins = {
  time: {
    additional: Optional<PluginTime, 'validate' | 'parse' | 'formatString' | 'formatTick' | 'formatShort'>[];
    enableDatePicker: boolean;
    getDefaultPlanEndDate: (start: Date) => Date | null;
    primary: PluginTime;
    ticks: {
      getTicks: (start: Date, stop: Date, count: number) => Date[];
      maxLabelWidth: number;
    };
  };
};
