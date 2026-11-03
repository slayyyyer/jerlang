-module(sigil).

-moduledoc ~S"""
    Sigil
    """.
-export([f/0, g/2, h/1]).

f() ->
    {~S"abc", ~b"abc"}.

g(L, M) ->
    [X || X <- L && Y <- M].

h(Map) ->
    #{K => V || K := V <- Map, V =/= asn1_NOVALUE}.
