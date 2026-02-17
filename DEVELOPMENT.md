# ReVoman Development Guide

## Commands to Build and Verify

```bash
# Full build including tests
./gradlew build

# Build without tests
./gradlew assemble

# Run all tests
./gradlew test

# Run specific test class
./gradlew test integrationTest --tests "com.salesforce.revoman.integration.pokemon.PokemonTest"

# Run specific test method  
./gradlew jvmTest --tests "com.salesforce.revoman.internal.postman.RegexReplacerTest.custom dynamic variables"

# Compile test classes only (for faster iteration)
./gradlew testClasses

# Fix code formatting
./gradlew spotlessApply
```

## Development Environment

- **JDK**: 21+ required for JVM target
- **Build System**: Gradle with version catalogs for dependency management
- **Targets**: JVM
