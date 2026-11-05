# Spring Boot embedding without Erlang

This example puts a JErlang node in a Spring Boot service. Maven compiles the
Erlang source during the build, Spring owns the node as a singleton bean, and a
controller calls an exported Erlang function for each HTTP request. A JDK is
the only toolchain required.

```sh
cd examples/spring-boot-embed
mvn clean verify
mvn spring-boot:run
```

```console
$ curl -s 'localhost:8080/api/greet?name=Spring'
{"message":"hello, Spring"}
```

The dependency is the published thin artifact:

```xml
<dependency>
  <groupId>io.github.slayyyyer</groupId>
  <artifactId>jerlang</artifactId>
  <version>3.0.0</version>
</dependency>
```

## The node is a bean

`JErlangConfiguration` starts one node and gives Spring its lifecycle:

```java
@Bean(destroyMethod = "close")
public Node jerlangNode(@Value("${jerlang.ebin}") String ebin) {
    Path codePath = Path.of(ebin).toAbsolutePath().normalize();
    return Node.start(NodeOptions.builder().codePath(codePath).build());
}
```

Spring creates the bean when the application context starts. When the context
stops, Spring calls `Node.close()`, which shuts down the schedulers, drains the
object handle table and resets the process-wide code path and module registry.
There is no second node and no hand-written shutdown hook in the example.

Only one node may be open in a JVM. Keep the bean singleton; do not create a
node per request or per test class.

## The request path

The controller converts the request parameter to an Erlang binary, calls
`greeter:greet/1` with a two-second deadline, and converts the returned binary
back to a Java `String`:

```java
Term reply = node.apply("greeter", "greet", TIMEOUT,
    Bridge.toTerm(name.getBytes(StandardCharsets.UTF_8)));

Object value = Bridge.toJava(reply);
return new Greeting(new String((byte[]) value, StandardCharsets.UTF_8));
```

`greeter.erl` is deliberately small; it proves the boundary rather than hiding
it behind a framework adapter:

```erlang
-module(greeter).
-export([greet/1]).

greet(Name) ->
    <<"hello, ", Name/binary>>.
```

## The build step

The POM runs JErlang's own compiler main class during `generate-resources`:

```xml
<executable>java</executable>
<arguments>
  <argument>-classpath</argument>
  <classpath/>
  <argument>org.jerlang.compiler.CompilerCommand</argument>
  <argument>-o</argument>
  <argument>${project.build.directory}/ebin</argument>
  <argument>${project.basedir}/src/main/erl/greeter.erl</argument>
</arguments>
```

That writes `target/ebin/greeter.beam` before the application starts. It does
not invoke `erlc`, so the build and run need no Erlang installation. The
`jerlang.ebin` property in `application.properties` points the node at that
directory; override it when the beams live elsewhere:

```sh
java -jar target/spring-boot-embed-1.0.0.jar --jerlang.ebin=/srv/jerlang/ebin
```

The `mvn clean verify` test starts the Spring context and a real HTTP server on
a random port, calls `/api/greet`, and asserts the response came back through
JErlang.

## Files

| File | What it is |
| --- | --- |
| `pom.xml` | Spring Boot parent, JErlang dependency, Erlang compile execution. |
| `src/main/erl/greeter.erl` | The Erlang module compiled by JErlang. |
| `src/main/java/example/SpringBootEmbedApplication.java` | Spring Boot entry point. |
| `src/main/java/example/JErlangConfiguration.java` | Creates and closes the singleton node. |
| `src/main/java/example/GreetingController.java` | HTTP-to-Erlang call and response record. |
| `src/test/java/example/GreetingControllerTest.java` | End-to-end HTTP test. |

## What this does not need

`greeter` uses only the built-in runtime, so the example needs no Erlang/OTP
installation at build or run time. If the module becomes a `gen_server` or uses
another OTP behaviour, add `NodeOptions.otpRoot(...)` and install Erlang/OTP.
That boundary is covered in [Embedding in a JVM service](../../docs/embedding.md).
