# A Spring Boot service with JErlang

This example is the Spring Boot version of the embedding path: Maven compiles
one Erlang module, Spring owns one JErlang node for the life of the application,
and an HTTP controller calls the module for each request. A JDK is the only
toolchain needed to build or run it.

```sh
cd examples/spring-boot-embed
mvn clean verify
mvn spring-boot:run
```

```console
$ curl -s 'localhost:8080/api/greet?name=Spring'
{"message":"hello, Spring"}
```

The service uses the published artifact:

```xml
<dependency>
  <groupId>io.github.slayyyyer</groupId>
  <artifactId>jerlang</artifactId>
  <version>3.0.0</version>
</dependency>
```

## The lifecycle belongs to Spring

`JErlangConfiguration` creates the node as a singleton bean and gives Spring
the destroy method:

```java
@Bean(destroyMethod = "close")
public Node jerlangNode(@Value("${jerlang.ebin}") String ebin) {
    Path codePath = Path.of(ebin).toAbsolutePath().normalize();
    return Node.start(NodeOptions.builder().codePath(codePath).build());
}
```

That is the whole integration contract. The node starts with the application
context, and `Node.close()` runs when the context stops. The node owns the
process-wide code path and module registry, so the application does not call
`CodePaths.reset()` or `ModuleRegistry.reset()` itself.

Only one node may be open in a JVM, which is why this is a singleton rather
than a request-scoped object. A second `Node.start` is rejected.

## The request path

The controller crosses the boundary with the same `Bridge` methods as the
Maven quickstart:

```java
Term reply = node.apply("greeter", "greet", Duration.ofSeconds(2),
    Bridge.toTerm(name.getBytes(StandardCharsets.UTF_8)));

Object value = Bridge.toJava(reply);
return new Greeting(new String((byte[]) value, StandardCharsets.UTF_8));
```

`greeter.erl` is intentionally small:

```erlang
-module(greeter).
-export([greet/1]).

greet(Name) ->
    <<"hello, ", Name/binary>>.
```

The controller owns the HTTP contract and the Erlang module owns the greeting.
A request parameter arrives in Java, becomes an Erlang binary, is computed by
Erlang, and returns as JSON.

## The Erlang build

The POM runs the compiler during `generate-resources`, using the same
`CompilerCommand` as the Maven quickstart:

```xml
<argument>org.jerlang.compiler.CompilerCommand</argument>
<argument>-o</argument>
<argument>${project.build.directory}/ebin</argument>
<argument>${project.basedir}/src/main/erl/greeter.erl</argument>
```

The generated `target/ebin/greeter.beam` is loaded from the code path in
`application.properties`. There is no `erlc` step and no committed beam.

The test starts a real HTTP server on a random port, calls `/api/greet`, and
asserts that the response came back through the embedded node.

## Run it as a jar

```sh
mvn clean package
java -jar target/spring-boot-embed-1.0.0.jar
```

Run that from `examples/spring-boot-embed`, or point the application at another
directory:

```sh
java -jar target/spring-boot-embed-1.0.0.jar --jerlang.ebin=/srv/jerlang/ebin
```

The example keeps the beams outside the jar so the code path remains a normal
directory. Packaging them into an application-specific artifact is a separate
build decision.

## What it does not do

The greeting module uses only the built-in runtime, so this example does not
need an Erlang/OTP installation. Running OTP's own `gen_server`, `supervisor` or
`application` still needs `NodeOptions.otpRoot(...)` and an OTP installation.
The [embedding guide](../embedding.md) covers that boundary, and the
[JUnit example](junit-harness.md) shows the smaller test-only shape.
