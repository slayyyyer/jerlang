-module(greeter).
-export([greet/1, product/2]).

greet(Name) ->
    <<"hello, ", Name/binary>>.

product(Left, Right) ->
    Left * Right.
