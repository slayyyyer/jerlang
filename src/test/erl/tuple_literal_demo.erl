%% Literal tuples returned from a function are moved as a whole. A two
%% element literal used to be mistaken for a register operand, so {ok, 42}
%% failed with "Cannot convert to integer".
%% Compile with: erlc -o src/test/resources src/test/erl/tuple_literal_demo.erl
-module(tuple_literal_demo).
-export([run/0, ok_pair/0, pair/0, nested/0, tagged/1, match/1, guard/1]).

ok_pair() -> {ok, 42}.
pair() -> {a, b}.
nested() -> {ok, {a, b}}.
tagged(Value) -> {ok, Value}.
guard(X) when X =:= {ok, 1} -> yes;
guard(_) -> no.

match({ok, Value}) -> Value;
match(_) -> none.

run() ->
    {ok, 42} = ok_pair(),
    {a, b} = pair(),
    {ok, {a, b}} = nested(),
    {ok, 7} = tagged(7),
    7 = match({ok, 7}),
    none = match({error, 7}),
    yes = guard({ok, 1}),
    no = guard({ok, 2}),
    ok.
