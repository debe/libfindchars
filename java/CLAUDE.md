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

**Important**: Requires **JDK 25** with `--enable-preview` and `--add-modules=jdk.incubator.vector`. Maven Surefire is pre-configured with these JVM args plus `--enable-native-access`. The build compiles with `--release 25`. Maven coordinates: `org.knownhosts:libfindchars-compiler`.

**macOS note**: Set `JAVA_HOME` explicitly if the default JDK is not 25:
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
```

## Gotchas

- `--add-modules=jdk.incubator.vector` is required for compilation *and* test execution
- Z3 native libraries are bundled via `javasmt-solver-z3` on macOS; CI downloads them separately for Linux
- When running bench tests standalone, build the csv module first or use `-am` from the reactor root
