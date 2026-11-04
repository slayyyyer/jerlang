-module(macros).
-define(ONE, 1).
-define(TWO, 2).
-define(PAIR(A, B), {A, B}).
-define(EMPTY,).
-define(ZERO(), zero).
-define(TEXT(A), ??A).
-define(MULTI,
    first,
    second).
-export([one/0, pair/0, empty/0, zero/0, text/0, multi/0, module/0, file/0, line/0]).
one() ->
    ?ONE + ?TWO.
pair() ->
    ?PAIR(?ONE, ?TWO).
empty() ->
    ?EMPTY ok.
zero() ->
    ?ZERO().
text() ->
    ?TEXT(foo(1, "a b", 'q x')).
multi() ->
    [?MULTI].
module() ->
    ?MODULE.
file() ->
    ?FILE.
line() ->
    ?LINE.
