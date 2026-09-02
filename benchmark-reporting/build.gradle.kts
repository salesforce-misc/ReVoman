plugins { id("revoman.benchmark-reporting") }

val consumerScorecardExecutable = configurations.named("consumerScorecardExecutable")

dependencies {
  add(
    consumerScorecardExecutable.name,
    project(path = ":benchmarks", configuration = "consumerScorecardExecutable"),
  )
}
