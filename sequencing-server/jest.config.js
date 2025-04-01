export default {
  transform: {
    '^.+\\.tsx?$': [
      'ts-jest',
      {
        diagnostics: {
          ignoreCodes: [],
        },
        useESM: true,
      },
    ],
  },
  testRunner: 'jest-circus/runner',
  // testRegex: "(/test/.*|(\\.|/)(test|spec))\\.(jsx?|tsx?)$",
  testRegex: '((\\.|/)(test|spec))\\.(jsx?|tsx?)$',
  testPathIgnorePatterns: ['/node_modules/', '/build/', '/plugins/'],
  coverageReporters: ['html'],
  setupFiles: ['dotenv/config', './src/polyfills.ts'],
  globalSetup: './jestGlobalSetup.js',
  setupFilesAfterEnv: ['jest-extended/all'],
  reporters: [
    'default',
    [ 'jest-junit', { outputName: "test-report.xml" } ],
    [
      'jest-html-reporters',
      {
        publicPath: '<rootDir>',
        pageTitle: 'sequencing-server Test Report',
        filename: 'test-report.html',
        inlineSource: true,
      },
    ],
  ],
  transformIgnorePatterns: ['[/\\\\]node_modules[/\\\\].+\\.(js|jsx)$'],
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
  collectCoverageFrom: ['src/**/*.ts'],
  coveragePathIgnorePatterns: [],
  extensionsToTreatAsEsm: ['.ts'],
  moduleNameMapper: {
    '^(\\.{1,2}/.*)\\.js$': '$1',
  },
};
