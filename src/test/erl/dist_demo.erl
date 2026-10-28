%% Exercises sending to and receiving from another node from compiled Erlang.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/dist_demo.erl
-module(dist_demo).

-export([echo_roundtrip/2, node_name/0, peer_nodes/0]).

%% Send {self(), Msg} to the registered process echo on Node and wait for its reply.
echo_roundtrip(Node, Msg) ->
    {echo, Node} ! {self(), Msg},
    receive
        {echo, Reply} -> Reply
    after 5000 ->
        timeout
    end.

node_name() ->
    node().

peer_nodes() ->
    nodes().
