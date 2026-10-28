# JErlang

[Erlang/OTP](https://github.com/erlang/otp) on the
[Java Virtual Machine](https://en.wikipedia.org/wiki/Java_virtual_machine) (JVM).

[![Build Status](https://travis-ci.org/jerlang/jerlang.svg?branch=master)](https://travis-ci.org/jerlang/jerlang)
[![Dependency Status](https://www.versioneye.com/user/projects/55acc9853065350023000481/badge.svg?style=flat)](https://www.versioneye.com/user/projects/55acc9853065350023000481)

## Roadmap

|Version|Focus           |Milestone                                         |Status|
|:-----:|----------------|--------------------------------------------------|:----:|
|[0.1](https://github.com/jerlang/jerlang/issues?q=milestone%3A%220.1+%28File+Format%29%22)    |File Format     |BEAM files can be parsed                          |:heavy_check_mark:|
|[0.2](https://github.com/jerlang/jerlang/issues?q=milestone%3A%220.2+%28Proof+of+Concept%29%22)    |Proof of Concept|A simple "hello world" can be interpreted         |:heavy_check_mark:|
|[0.3](https://github.com/jerlang/jerlang/issues?q=milestone%3A%220.3+%28Interpreter%29%22)    |Interpreter     |All BEAM opcodes are basically implemented        |:heavy_check_mark:|
|[0.4](https://github.com/jerlang/jerlang/milestones/0.4%20(Network))    |Network         |Multi-node applications can be interpreted        ||
|[0.5](https://github.com/jerlang/jerlang/milestones/0.5%20(Compiler))    |Compiler        |BEAM files can be compiled to Java bytecode       ||
|[0.6](https://github.com/jerlang/jerlang/milestones/0.6%20(Scheduler))    |Scheduler       |Soft realtime support                             ||
|[0.7](https://github.com/jerlang/jerlang/milestones/0.7%20(Compatibility))    |Compatibility   |JErlang is fully compatible with native Erlang/OTP||
|[0.8](https://github.com/jerlang/jerlang/milestones/0.8%20(Documentation))    |Documentation   |The documentation is complete and awesome         ||
|[0.9](https://github.com/jerlang/jerlang/milestones/0.9%20(Performance))    |Performance     |Performance is comparable to native Erlang/OTP    ||
|[1.0](https://github.com/jerlang/jerlang/milestones/1.0%20(Stability))    |Stability       |JErlang is production ready                       ||

## Java interoperability

Erlang code runs on the JVM itself, so calling Java is a direct reflective
call: no external term format, no sockets, no separate node.

```erlang
Builder = java:new("java.lang.StringBuilder", []),
java:call(Builder, "append", ["hello"]),
"hello" = java:call(Builder, "toString", []),
java:release(Builder).
```

| Function | Purpose |
|----------|---------|
| `java:new(Class, Args)` | Call a constructor, returns an object handle |
| `java:call(Object, Method, Args)` | Call an instance method |
| `java:call_static(Class, Method, Args)` | Call a static method |
| `java:get(Object, Field)` / `java:set(Object, Field, Value)` | Instance fields |
| `java:get_static(Class, Field)` / `java:set_static(Class, Field, Value)` | Static fields |
| `java:release(Object)` | Drop the handle, allowing the JVM to collect the object |

Values are converted on the way in and out: integers to `int`/`long`/`short`/
`byte`/`double`/`float`/`BigInteger`/`BigDecimal`, floats to `double`/`float`,
strings and atoms to `String`, binaries to `byte[]`, lists to collections or
arrays, tuples to `Object[]`, `true`/`false` to `boolean`, and `null` back to
the atom `undefined`. Java exceptions surface as
`error({java_exception, ClassName, Message})`.

The reverse direction is `org.jerlang.api.Bridge`:

```java
Term result = Bridge.call("lists", "reverse", List.of(Integer.of(1), Integer.of(2)));
```

## Compiled Erlang

JErlang does not compile Erlang source. Compile it with `erlc` and load the
resulting `.beam` from the working directory:

```
erlc -o src/test/resources src/test/erl/bridge_demo.erl
```

Modules built by current OTP releases need the loader support described in
[docs/beam-compatibility.md](docs/beam-compatibility.md), which also lists the
opcodes that are still missing.

## Build

JErlang builds with Maven and needs a JDK 17 or newer; the tests run on the
JVM alone and need no installed Erlang.

```
mvn test
mvn package
java -jar target/jerlang-0.3.0.jar
```

## Contribute

JErlang uses the [C4.1 (Collective Code Construction Contract) process](http://rfc.zeromq.org/spec:22) for contributions.

## Discuss

* **#jerlang** on [Freenode](http://www.freenode.net/) [IRC](https://en.wikipedia.org/wiki/Internet_Relay_Chat)
* [Issue tracker](https://github.com/jerlang/jerlang/issues)

## See also

* [erjang: A JVM-based Erlang VM](https://github.com/trifork/erjang)
* [JErlang: Erlang with Joins](http://www.doc.ic.ac.uk/~susan/jerlang/)

## License

[Apache 2.0 License](LICENSE)
