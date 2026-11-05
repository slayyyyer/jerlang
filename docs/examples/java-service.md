# Erlang calls Java

`examples/java-service` is the other direction: a compiled Erlang module calls
static methods of a plain Java class through `java:call_static/3`. No port, no
external term format, no second node — the call is a reflective invocation in
the same process.

The useful consequence is that Java code written for its own sake, with no
awareness of Erlang, is callable from Erlang as it stands. `Service` here does
not import anything from `org.jerlang`.

## The Java side

```java
/**
 * A plain Java class with static methods. service.erl reaches it through
 * java:call_static/3, so nothing on this side knows about Erlang.
 */
public final class Service {

    private Service() {
    }

    public static String greet(String name) {
        return "hello, " + name;
    }

    public static int scale(int value, int factor) {
        return value * factor;
    }

}
```

## The Erlang side

```erlang
%% Erlang calling Java. The java module converts the arguments to the
%% parameter types of the static method and converts the result back.
-module(service).
-export([greet/1, scale/2]).

greet(Name) ->
    java:call_static("Service", "greet", [Name]).

scale(Value, Factor) ->
    java:call_static("Service", "scale", [Value, Factor]).
```

`service.erl` is deliberately thin: the interesting work is the conversion.
`greet/1` passes an Erlang string (a charlist), which becomes a `java.lang.String`
for the `String` parameter; `scale/2` passes two integers, which are matched to
the `int` parameters of the `int, int` overload and come back as an Erlang
integer.

## Driving it from Java

```java
public static void main(String[] args) {
    try (Node node = Node.start(NodeOptions.builder().reflection(true).build())) {
        Object greeting = Bridge.toJava(node.apply("service", "greet", Str.of("erlang")));
        Object scaled = Bridge.toJava(node.apply("service", "scale",
            Integer.of(6), Integer.of(7)));
        System.out.println(greeting);
        System.out.println("6 * 7 = " + scaled);
    }
}
```

## What each part is doing

| Line | Why it is there |
| --- | --- |
| `NodeOptions.builder().reflection(true).build()` | The `java` module is **not** registered by default. A node that does not ask for reflection has no `java:new/2` and cannot reach arbitrary JVM classes; `service.erl` would fail with `undef`. |
| `Str.of("erlang")` | Builds an Erlang string term. It becomes `"erlang"` on the Java side. |
| `Integer.of(6)`, `Integer.of(7)` | Erlang integers. The bridge picks `int, int` for `scale/2` by conversion distance, not by method order. |
| `Bridge.toJava(...)` | Converts the returned term back to a Java value for printing. |

Argument conversion happens **before** the method is selected: an Erlang integer
prefers `int`, then the other integral types, then floating point; a `char`
parameter accepts a one-character string; an enum parameter accepts an atom with
the constant's name. Ties are broken by parameter specificity and then by sorted
signature, so the same call selects the same method on every JVM rather than
depending on the order `Class#getMethods` happens to return. Varargs are
supported for a trailing `Object...` parameter only.

## Running it

```sh
mvn -B -DskipTests compile
bash examples/run.sh
```

Expected output, for both examples together:

```text
--- Java calls Erlang ---
counter: 2
--- Erlang calls Java ---
hello, erlang
6 * 7 = 42
```

This example needs no Erlang installation at run time — only `erlc` to produce
`service.beam` — because it uses no OTP module. `run.sh` requires `erl` anyway,
since the other example does.

## Errors and limits

- A Java exception surfaces in Erlang as `error({java_exception, ClassName, Message})`.
  That reason is reserved for exceptions; an exhausted runtime resource reports
  `system_limit` instead.
- An Erlang `exit` does not stop the JVM, so a failing Erlang process cannot take
  the host process down with it.
- Objects without a value mapping come back as object handles, which are
  node-local and cannot be sent to another node. Release them with
  `java:release/1`; `Node.close()` drains whatever is left.
- Nothing is reachable by reflection that the JVM's module system does not
  export to this code. The runnable `all` jar shades `module-info.class`, which
  Maven warns about at build time ("Shading will break its strong
  encapsulation"), so reflection into the JDK's own internals is not a supported
  direction. The thin library jar used as a dependency is not shaded.

The full table of conversions, in both directions, is in
[Java interoperability](../java-interop.md) — and it is enforced: `mvn verify`
runs `japicmp` and fails on a change without a version bump.
