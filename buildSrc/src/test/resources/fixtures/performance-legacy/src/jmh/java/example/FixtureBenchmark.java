package example;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class FixtureBenchmark {
    @Setup(Level.Trial)
    public void failClosedCanary() {
        if (Boolean.getBoolean("fixture.fail-setup")) {
            throw new IllegalStateException("fixture setup failure");
        }
    }

    @Benchmark
    public int measure() {
        return FixtureApplication.value();
    }
}
