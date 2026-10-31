# Java interoperability

JErlang runs on the JVM, so Erlang code calls Java through reflection and Java
code calls Erlang through `org.jerlang.api.Bridge`. This page defines the value
mapping and the handle rules. The tables are the 0.8 compatibility contract:
changing a row needs a version bump.

## Erlang to Java

| Erlang term | Java type | Notes |
|-------------|-----------|-------|
| integer that fits in 32 bits | `java.lang.Integer` | |
| integer that fits in 64 bits | `java.lang.Long` | |
| larger integer | `java.math.BigInteger` | |
| float | `java.lang.Double` | |
| atom `true` / `false` | `java.lang.Boolean` | |
| atom `undefined` | `null` | |
| any other atom | `java.lang.String` | Returning a string becomes an Erlang string, not the original atom. |
| string (charlist) | `java.lang.String` | `Str` and a list of character integers both convert. |
| binary | `byte[]` | |
| list | `java.util.List` | An `ArrayList`; the nil list becomes an empty list. |
| tuple | `Object[]` | |
| map | `java.util.Map` | A `LinkedHashMap`; keys and values are converted recursively. |
| object handle | the live Java object | Handles are references created by `java:new`, not values. |

## Java to Erlang

| Java type | Erlang term | Notes |
|-----------|-------------|-------|
| `null` | atom `undefined` | |
| `java.lang.Boolean` | atom `true` / `false` | |
| `java.lang.String` | string | |
| `java.lang.Character` | one-character string | |
| `Byte`, `Short`, `Integer`, `Long` | integer | |
| `java.math.BigInteger` | integer | |
| `Float`, `Double` | float | |
| `byte[]` | binary | |
| `Object[]` | tuple | |
| `java.util.Collection` | list | |
| `java.util.Map` | map | Keys and values are converted recursively. |
| any other object | object handle | Use `java:release/1` when the Erlang side is done. |

Java does not distinguish integer widths in Erlang, so `int`, `long` and
`BigInteger` all become one Erlang integer type and the width is chosen again
on the way back.

## Arguments and overload resolution

An argument is converted to the declared parameter type before a method or
constructor is chosen. For an Erlang integer the preference is `int`, then the
other integral types, then floating point. When several overloads accept the
arguments, the one with the lowest conversion distance wins; an `Object`
parameter gets the generic conversion above. A `char` parameter accepts a
one-character string or a code point, and an enum parameter accepts an atom
with the constant's name.

Java exceptions surface as an Erlang `error` with reason
`{java_exception, ClassName, Message}`. An Erlang exit does not stop the JVM.

## Object handles

`java:new/2` and any Java value that has no value mapping above are returned as
an object handle. One handle exists per Java object identity, and
`java:release/1` drops it; a call on a released handle raises an error.
Handles are node-local and are not Erlang terms that can be sent to another
node.

The handle table currently has no owner and is not bounded: a program that
never releases handles keeps its Java objects alive. Ownership and a documented
release policy are still open 0.8 work, as is the embedding API.

## Stability

`Bridge.toTerm/1` and `Bridge.toJava/1` are the conversion entry points for
embedding. `TypeMappingTest` has one test per row above, and a change to a row
must come with a version bump.
