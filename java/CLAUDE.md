# Java implementation

All Java build commands run from the `java/` directory:

```bash
# Build entire project (requires JDK 25)
cd java && ./mvnw clean install

# Build without tests
cd java && ./mvnw clean install -DskipTests

# Run all tests
cd java && ./mvnw test
```

**Important**: Requires **JDK 25** with `--add-modules=jdk.incubator.vector` (the incubator Vector API — no preview features, so no `--enable-preview`). Maven Surefire is pre-configured with this plus `--enable-native-access`. The build compiles with `--release 25`; the artifact also runs on JDK 26. Maven coordinates: `org.knownhosts:libfindchars-compiler`.

**macOS note**: Set `JAVA_HOME` explicitly if the default JDK is not 25:
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
```

## Running examples

```bash
cd java && ./mvnw -pl libfindchars-examples -am compile
./mvnw -pl libfindchars-examples exec:exec -Dexample=org.knownhosts.libfindchars.examples.FindLiteralsAndPositions
```

## Gotchas

- `--add-modules=jdk.incubator.vector` is required for compilation *and* test execution
- The `javasmt-solver-z3` jar does NOT bundle the Z3 native library. On this machine it loads from `~/Library/Java/Extensions/{libz3,libz3java}.dylib` (on the JVM's default `java.library.path`); CI downloads the platform classifier from Maven Central and passes `-Dz3.argline=-Djava.library.path=...` (macOS) or `LD_LIBRARY_PATH` (Linux)
- When running bench tests standalone, build the csv module first or use `-am` from the reactor root
