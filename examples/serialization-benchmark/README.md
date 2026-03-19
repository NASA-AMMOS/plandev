# Serialization Benchmark

Quick benchmarks to figure out where we're spending time on serialization. Turns out it was mostly the old javax.json library being slow.

## Running

```bash
./gradlew :examples:serialization-benchmark:test
```


## What it tests

- Simple records (Vector3D with 3 doubles)
- Complex nested stuff (activity state with lists, maps, nested records)
- Large records (20+ fields)
- Collections (lists and maps with 100 items)

The test data includes spacecraft state types like quaternions, ephemeris data, telemetry points, etc. Pretty representative of actual Aerie resources.

## Results

We got about 14-32x faster by switching from javax.json to Jackson and caching constructors in RecordValueMapper. Details in the other markdown files.
