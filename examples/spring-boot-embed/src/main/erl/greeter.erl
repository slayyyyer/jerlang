-module(greeter).
-export([greet/1]).

greet(Name) ->
    <<"hello, ", Name/binary>>.
