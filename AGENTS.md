# ReVoman – An API Orchestration Engine for the JVM

Kotlin/Gradle project for executing Postman collections on JVM. Requires JDK 25+.

## Project Structure

- `revoman/src/main/kotlin/` - Published library source code
- `revoman/src/test/` - Unit tests (Kotest)
- `revoman/src/integrationTest/` - Integration tests
- `benchmarks/` - Reusable kotlinx-benchmark scenarios
- `benchmark-reporting/` - Generic benchmark comparison CLI
- `gradle/libs.versions.toml` - Version catalog for dependencies
- `build-logic/` - Custom Gradle conventions

## Development

See @DEVELOPMENT.md for the development guide

## Style

See @STYLE.md for the style guide

## Test Automation

- Make sure all new code is covered with appropriate tests
- Make sure all the existing tests pass after new code changes
- Run `./gradlew qodanaScan` (Qodana static analysis) before pushing — see @DEVELOPMENT.md > Static Analysis

## Logging

- Make sure to add appropriate logging for all the features
