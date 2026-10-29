%% Conformance corpus: tuple records, defaults, updates and pattern matching.
%% Compile with: erlc -o src/test/resources/corpus src/test/erl/corpus/corpus_records.erl
-module(corpus_records).

-export([run/0]).

-record(p, {x, y = 5, z}).

run() ->
    P = #p{x = 1, z = 3},
    #p{x = 1, y = 5, z = 3} = P,
    1 = P#p.x,
    5 = P#p.y,

    P2 = P#p{x = 9},
    #p{x = 9, y = 5, z = 3} = P2,
    9 = P2#p.x,

    P3 = P2#p{y = 7, z = 8},
    #p{z = Z} = P3,
    8 = Z,

    #p{y = Default} = #p{x = 0, z = 0},
    5 = Default,
    ok.
