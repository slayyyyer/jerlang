%% Exercises the Java bridge from real Erlang source.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/bridge_demo.erl
-module(bridge_demo).

-export([run/0, greeting/1, abs_value/0]).

%% Build a Java StringBuilder, append to it and read it back.
greeting(Who) ->
    Builder = java:new("java.lang.StringBuilder", []),
    java:call(Builder, "append", ["hello, "]),
    java:call(Builder, "append", [Who]),
    Text = java:call(Builder, "toString", []),
    java:release(Builder),
    Text.

%% Call a static Java method.
abs_value() ->
    java:call_static("java.lang.Math", "abs", [-7]).

run() ->
    {greeting("erlang"), abs_value()}.
