# Build 29 performance results

Build 29 adds repeatable startup benchmarks, generated Baseline Profiles, and privacy-safe JankStats
monitoring for the phone and HUD surfaces. Jank records contain only the fixed surface label and
frame duration; they never receive chat text, identifiers, paths, tool data, or credentials.

## Pixel 9 Pro startup benchmark

Measured on a Pixel 9 Pro running Android API 37 with the benchmark release and generated Baseline
Profile. Each mode used five iterations and emitted one Perfetto trace per iteration.

| Metric | Minimum | Median | Maximum |
| --- | ---: | ---: | ---: |
| Warm time to initial display | 70.1 ms | 77.8 ms | 110.1 ms |
| Cold time to initial display | 336.6 ms | 397.8 ms | 430.4 ms |

The device CPU was not frequency-locked, so these values are an on-device regression baseline rather
than laboratory-grade cross-device numbers. Build 25 did not yet contain the benchmark harness; no
unsupported before/after startup claim is made.

## Baseline Profile

- 19,828 generated rules from the permission-complete startup journey.
- Profile generation and both startup benchmarks completed with zero test failures.
- The benchmark setup grants the same runtime permissions required by the product so permission UI is
  excluded from the measured startup path.

Run the measurements with:

```bash
./gradlew :phone-app:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.class=com.clawsses.benchmark.BaselineProfileGenerator
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.clawsses.benchmark.StartupBenchmark
```
