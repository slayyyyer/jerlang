%% The JErlang side of the mixed cluster. This module is compiled by JErlang;
%% mixed_peer.erl is compiled by the OTP installation that runs the peer.
-module(mixed_jerlang).

-export([echo/1, call_peer/2]).

echo(Value) ->
    Value.

call_peer(Peer, Value) ->
    erpc:call(Peer, mixed_peer, echo, [Value]).
