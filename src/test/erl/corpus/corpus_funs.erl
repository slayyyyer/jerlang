%% Conformance corpus: anonymous functions, closures and higher-order calls.
%% Compile with: erlc -o src/test/resources/corpus src/test/erl/corpus/corpus_funs.erl
-module(corpus_funs).

-export([run/0]).

run() ->
    Add = fun(A, B) -> A + B end,
    5 = Add(2, 3),

    Inc = fun(X) -> X + 1 end,
    [2, 3, 4] = [Inc(N) || N <- [1, 2, 3]],

    Apply = fun(F, X) -> F(X) end,
    6 = Apply(Inc, 5),

    Make = fun(N) -> fun() -> N end end,
    Seven = Make(7),
    7 = Seven(),

    [3, 7] = sum_pairs([{1, 2}, {3, 4}], []),
    ok.

sum_pairs([], Acc) ->
    lists:reverse(Acc);
sum_pairs([{A, B} | Tail], Acc) ->
    sum_pairs(Tail, [A + B | Acc]).
