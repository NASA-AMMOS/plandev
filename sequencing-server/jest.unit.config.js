import config from './jest.config.js';

export default {
  ...config,
  globalSetup: undefined,
  reporters: ['default'],
  testRegex: '\\.unit\\.spec\\.ts$',
};
