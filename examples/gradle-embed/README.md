# Gradle embedding without Erlang

This is the Gradle counterpart of [`maven-embed`](../maven-embed/README.md). It
keeps Erlang beside Java: `.erl` sources under `src/main/erl`, compiled by
JErlang during the build, and a Java `main` that starts a node and calls the
module in the same process. A JDK is the only toolchain required.

```sh
cd examples/gradle-embed
gradle test
```

The test checks that `build/ebin/greeter.beam` was produced by this build and
then calls it through `org.jerlang.api.Node`. To see the demo output:

```sh
gradle -q run
```

```text
hello, Java
6 * 7 = 42
```

## The build step

`build.gradle` declares the published artifact and registers `compileErlang` as
the JErlang compiler's `JavaExec` entry point:

```gradle
dependencies {
    implementation 'io.github.slayyyyer:jerlang:3.0.0'
}

def erlangOut = layout.buildDirectory.dir('erlang')
def sources = fileTree('src/main/erl') { include '**/*.erl' }

tasks.register('compileErlang', JavaExec) {
    classpath = configurations.compileClasspath
    mainClass = 'org.jerlang.compiler.CompilerCommand'
    args = ['-o', erlangOut.get().asFile.absolutePath] +
        sources.files.collect { it.absolutePath }
}
```

That is the same compiler main class the runnable jar's `jerlang compile`
command invokes; it runs from Gradle's dependency classpath. It never calls
`erlc`, so the build does not depend on an Erlang installation.

The task compiles into `build/erlang` and replaces `build/ebin` only after the
whole compile succeeds; `classes` depends on it. A failed source compile
therefore leaves no beam for the JUnit test to reuse, and a clean checkout
cannot pass on a committed artifact.

## The Java side

```java
try (Node node = Node.start(NodeOptions.builder()
        .codePath(Path.of(ebin)).build())) {
    byte[] bytes = (byte[]) Bridge.toJava(node.apply("greeter", "greet",
        Bridge.toTerm("Java".getBytes(StandardCharsets.UTF_8))));
    System.out.println(new String(bytes, StandardCharsets.UTF_8));
}
```

`Node` owns the code path. Starting a node resets the process-wide path and
module registry, then applies the directories in `NodeOptions`; there is no
reset recipe to copy from the example.

## Files

| File | What it is |
| --- | --- |
| `build.gradle` | One dependency, the JErlang compile task, and the test setup. |
| `src/main/erl/greeter.erl` | The Erlang module; compiled by JErlang, not `erlc`. |
| `src/main/java/example/EmbedDemo.java` | Starts the node and calls the module. |
| `src/test/java/example/EmbedDemoTest.java` | Proves the beam was built and callable. |

## What this does not need

`greeter` uses only the built-in runtime, so this example needs no Erlang
installation at build or run time. If the module is a `gen_server` or uses
another OTP behaviour, add OTP's `stdlib` and `kernel` to the code path and
install Erlang/OTP; that boundary is covered in the
[embedding plan](../../docs/embedding.md).
