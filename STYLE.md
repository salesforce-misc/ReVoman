# ReVoman Style Guide

## Patterns in this codebase

- This codebase heavily uses Functional programming concepts

## Functional Programming Patterns

- Reduce state mutation as much as possible. Prefer state transformation
- Follow the functional style from the existing codebase
- Chain operations using sequences: prefer `.asSequence()` for multiple transformations
- Avoid imperative loops if possible
- Build immutable data flow: pass state through parameters, return a new state instead of mutating
- Use functional combinators: `map`, `filter`, `flatMap`, `fold`, `firstOrNull` over loops
- Prefer single-expression functions that return directly from when/if expressions
- Prefer direct recursion with parameters over complex state objects
- Don't overengineer functional programming code

## Kotlin Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use four spaces for indentation (consistent across all files)
- Name test functions as `testXxx` (no backticks for readability)
- Use descriptive variable and function names
- Prefer functional programming patterns where appropriate
- Use type-safe builders and DSLs for configuration
- Document public APIs with KDoc comments
- NEVER suppress compiler warnings without a good reason
- Always use `when` expressions over `if-else` chains
- Use ranges: `downTo`, `until`, `in`, `indices` for iterations
- String operations: use `substring(range)` with IntRange, not separate indices
- Choose `firstOrNull` over complex sequence chains when finding a first valid element
- Collection operations: use `+` operator for adding to immutable collections
- Use `?.let { }` with elvis `?:` for clean null handling
- Use `firstOrNull()` with elvis `?:` for fallback values
- While generating Kotlin code, if not obvious, specify variable types and function return types explicitly. Use Named parameters only in ambiguous situations.
