%% Exercises anonymous functions from real Erlang source.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/funs_demo.erl
-module(funs_demo).

-export([run/0, adder/1, twice/2]).

%% A fun that captures a free variable.
adder(N) ->
    fun(X) -> X + N end.

%% A higher order function: applies the given fun twice.
twice(F, X) ->
    F(F(X)).

run() ->
    Add2 = adder(2),
    Double = fun(X) -> X * 2 end,
    {Add2(10), twice(Double, 5)}.
