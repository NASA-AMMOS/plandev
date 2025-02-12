import type { Subscriber, Unsubscriber, Updater } from 'svelte/store';

export type GqlSubscribable<T> = {
  setVariables: (newVariables: QueryVariables) => void;
  subscribe: (next: Subscriber<T>) => Unsubscriber;
  filterValueById(id: number): void;
  updateValue(fn: Updater<T>): void;
};

export type NextValue<T> = { [key: string]: T };

export type QueryVariables = Record<string, any>;

export type Subscription<T> = {
  next: Subscriber<T>;
  unsubscribe: Unsubscriber;
};
