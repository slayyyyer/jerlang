-module(conditional).
-define(DEFINED, yes).
-export([a/0, b/0, c/0, d/0]).
-ifdef(DEFINED).
a() -> defined.
-else.
a() -> not_defined.
-endif.
-ifndef(UNDEFINED).
b() -> not_defined.
-else.
b() -> defined.
-endif.
-ifdef(UNDEFINED).
c() ->
    no.
-else.
-ifdef(DEFINED).
c() ->
    nested_yes.
-else.
c() ->
    nested_no.
-endif.
-endif.
-ifndef(DEFINED).
d() -> no.
-else.
d() -> yes.
-endif.
