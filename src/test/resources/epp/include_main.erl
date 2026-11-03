-module(include_main).
-include("include_header.hrl").
-export([value/0, from_header/0]).
value() ->
    ?HEADER_VALUE.
from_header() ->
    ?HEADER_FUN(2).
