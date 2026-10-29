%% Conformance corpus: map construction, update, pattern matching and lookup.
%% Compile with: erlc -o src/test/resources/corpus src/test/erl/corpus/corpus_maps.erl
-module(corpus_maps).

-export([run/0]).

run() ->
    M0 = #{a => 1},
    #{a := 1} = M0,
    1 = maps:get(a, M0),

    M1 = M0#{b => 2},
    2 = maps:get(b, M1),

    M2 = M1#{a := 10},
    #{a := A, b := B} = M2,
    10 = A,
    2 = B,

    Keys = [K || {K, _} <- [{a, 1}, {b, 2}]],
    [a, b] = Keys,
    ok.
