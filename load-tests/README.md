# PlanDev Load Testing

This directory contains code and configuration for load testing PlanDev deployments.

Load tests are written and run using the [k6](https://k6.io/open-source/) tool.

## Goals of load testing

Load testing of PlanDev aims to accomplish three main things:
 - Validation that the system can in fact handle the number of users we advertise.
 - Gathering of "trending" data, e.g. over the last 6 months of development, have simulations gotten faster?
 - Experimental stress testing, e.g. can we induce this deadlock by requesting 100 simulations at once.

## Running tests

The main test scenarios are defined in `src/load-test.ts`, and can be run using the `./load-test.sh` script.

The script will download `k6` along with an extension [`xk6-dashboard`](https://github.com/grafana/xk6-dashboard) that we use to visualize the test results, as well as install dependencies for webpack that we use to transpile TypeScript tests into pure JavaScript tests that `k6` can run.

The example load tests in `load-test.ts` depend on a few shared definitions from the `../e2e-test` folder (GQL queries, URLs, etc), so running these tests requires a full copy of the PlanDev repo locally. Additionally, the tests upload the example `banananation` mission model, so you must generate this build artifact with e.g. `./gradlew assemble --parallel` in the root of PlanDev before running load tests.

At the beginning of the load test typescript file, we define configuration options for the tests defined in that file. Here, you can change the number of virtual users / iterations as desired. The current values are set fairly low, in order to run within the resource limited Github Actions runner.

## Writing tests

Using the existing tests as examples, you can easily add new load testing scenarios (that will run in parallel with existing tests) to the existing `load-test.ts`, or create an entirely new test suite that can run separately (e.g. `soak-test.ts`). When designing tests, keep [these recommended load test types in mind](https://k6.io/docs/test-types/load-test-types/). The example tests in `load-test.ts` should act as a good starting point for designing your own tests; the [`k6` docs](https://k6.io/docs/) are also a great resource.

## Visualizing results

`k6` supports several output formats and ways to [visualize results](https://k6.io/blog/ways-to-visualize-k6-results/), in this directory we demo just one. The load testing script will call `k6` with a few flags to output test results in both a numerical JSON and visual HTML format.

Additionally, while the load tests are running, `k6` will spin up a web server at `http://localhost:5665`, which you can use to visualize the current running tests. Note that `k6` will wait to exit the test run and show final statistics until the live dashboard is no longer being accessed.

The numerical JSON output is meant for further analysis, i.e. looking at trends in historical data, while the HTML dashboard is useful to quickly visualize results.

The `xk6-dashboard` extension can be customized using the `./.dashboard.js` file, although the docs are lacking since this extension hasn't been upstreamed to `k6` proper yet. The `.dashboard.js` file in this directory simply shows an example visualization of our custom `effective_arg_duration` metric. The example [`.dashboard.js`](https://github.com/grafana/xk6-dashboard/blob/master/.dashboard.js) as well as the default chart [`boot.js`](https://github.com/grafana/xk6-dashboard/blob/master/assets/ui/boot.js) file are good references if you want to write additional custom charts. However, if more advanced data visualization is desired, I'd recommended to instead visualize the JSON output using a tool like [ChartJS](https://www.chartjs.org/), as that will have much better documentation for advanced workflows.

## Future work
 - [ ] More custom metrics (e.g. total simulation time)
 - [ ] Run load tests on a long-running dev deployment
 - [ ] Store historical data in something like [Prometheus](https://prometheus.io/) / [Grafana](https://grafana.com/oss/grafana/)
 - [ ] Correlate load test results with logs and container resource usage
