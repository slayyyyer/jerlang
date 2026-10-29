%% Exercises the native gen_udp and inet modules.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/gen_udp_demo.erl
-module(gen_udp_demo).

-export([roundtrip/0, active_roundtrip/0, connected_roundtrip/0,
         list_roundtrip/0, timeout_test/0, getopts_test/0,
         multicast_roundtrip/0, multicast_setopts/0,
         ipv6_multicast_roundtrip/1]).

roundtrip() ->
    {ok, Receiver} = gen_udp:open(0, [binary, {active, false}]),
    {ok, {_, Port}} = inet:sockname(Receiver),
    {ok, Port} = inet:port(Receiver),
    {ok, Sender} = gen_udp:open(0, [binary, {active, false}]),
    ok = gen_udp:send(Sender, {127, 0, 0, 1}, Port, <<"ping">>),
    {ok, {{127, 0, 0, 1}, _, <<"ping">>}} = gen_udp:recv(Receiver, 4, 2000),
    ok = gen_udp:close(Sender),
    ok = gen_udp:close(Receiver),
    ok.

active_roundtrip() ->
    {ok, Receiver} = gen_udp:open(0, [binary, {active, true}]),
    {ok, {_, Port}} = inet:sockname(Receiver),
    {ok, Sender} = gen_udp:open(0, [binary, {active, false}]),
    ok = gen_udp:send(Sender, {127, 0, 0, 1}, Port, <<"active">>),
    receive
        {udp, Receiver, {127, 0, 0, 1}, _, <<"active">>} -> ok
    after 2000 ->
        error(active_datagram_timeout)
    end,
    ok = gen_udp:close(Sender),
    ok = gen_udp:close(Receiver),
    ok.

connected_roundtrip() ->
    {ok, Receiver} = gen_udp:open(0, [binary, {active, false}]),
    {ok, {_, Port}} = inet:sockname(Receiver),
    {ok, Sender} = gen_udp:open(0, [binary, {active, false}]),
    ok = gen_udp:connect(Sender, {127, 0, 0, 1}, Port),
    {ok, {{127, 0, 0, 1}, Port}} = inet:peername(Sender),
    ok = gen_udp:send(Sender, <<"connected">>),
    {ok, {{127, 0, 0, 1}, _, <<"connected">>}} = gen_udp:recv(Receiver, 9, 2000),
    ok = gen_udp:close(Sender),
    ok = gen_udp:close(Receiver),
    ok.

list_roundtrip() ->
    {ok, Receiver} = gen_udp:open(0, [{active, false}]),
    {ok, {_, Port}} = inet:sockname(Receiver),
    {ok, Sender} = gen_udp:open(0, [{active, false}]),
    ok = gen_udp:send(Sender, {127, 0, 0, 1}, Port, "hello"),
    {ok, {{127, 0, 0, 1}, _, "hello"}} = gen_udp:recv(Receiver, 5, 2000),
    ok = gen_udp:close(Sender),
    ok = gen_udp:close(Receiver),
    ok.

timeout_test() ->
    {ok, Receiver} = gen_udp:open(0, [binary, {active, false}]),
    {error, timeout} = gen_udp:recv(Receiver, 0, 100),
    ok = gen_udp:close(Receiver),
    ok.

getopts_test() ->
    {ok, Receiver} = gen_udp:open(0, [binary, {active, false}]),
    {ok, [{active, false}]} = inet:getopts(Receiver, [active]),
    {error, enotconn} = inet:peername(Receiver),
    ok = inet:setopts(Receiver, [{active, true}]),
    {ok, [{active, true}]} = inet:getopts(Receiver, [active]),
    ok = gen_udp:close(Receiver),
    ok.

multicast_roundtrip() ->
    Group = {239, 255, 42, 99},
    {ok, Receiver} = gen_udp:open(0,
        [binary, {active, false}, {reuseaddr, true},
         {multicast_loop, true},
         {add_membership, {Group, {0, 0, 0, 0}}}]),
    {ok, {_, Port}} = inet:sockname(Receiver),
    {ok, Sender} = gen_udp:open(0,
        [binary, {active, false}, {multicast_ttl, 1},
         {multicast_loop, true}]),
    ok = gen_udp:send(Sender, Group, Port, <<"multicast">>),
    {ok, {_, _, <<"multicast">>}} = gen_udp:recv(Receiver, 9, 3000),
    ok = gen_udp:close(Sender),
    ok = gen_udp:close(Receiver),
    ok.

multicast_setopts() ->
    Group = {239, 255, 42, 100},
    {ok, Receiver} = gen_udp:open(0,
        [binary, {active, false}, {reuseaddr, true},
         {multicast_loop, true}]),
    {ok, {_, Port}} = inet:sockname(Receiver),
    ok = inet:setopts(Receiver, [{add_membership, {Group, {0, 0, 0, 0}}}]),
    {ok, Sender} = gen_udp:open(0,
        [binary, {active, false}, {multicast_loop, true}]),
    ok = gen_udp:send(Sender, Group, Port, <<"setopts">>),
    {ok, {_, _, <<"setopts">>}} = gen_udp:recv(Receiver, 7, 3000),
    ok = inet:setopts(Receiver, [{drop_membership, {Group, {0, 0, 0, 0}}}]),
    ok = gen_udp:close(Sender),
    ok = gen_udp:close(Receiver),
    ok.

ipv6_multicast_roundtrip(IfIndex) ->
    Group = {16#ff12, 0, 0, 0, 0, 0, 0, 16#1234},
    {ok, Receiver} = gen_udp:open(0,
        [inet6, binary, {active, false}, {reuseaddr, true},
         {multicast_if, IfIndex}, {multicast_loop, true},
         {add_membership, {Group, IfIndex}}]),
    {ok, {_, Port}} = inet:sockname(Receiver),
    {ok, Sender} = gen_udp:open(0,
        [inet6, binary, {active, false}, {multicast_if, IfIndex},
         {multicast_loop, true}, {multicast_ttl, 1}]),
    ok = gen_udp:send(Sender, Group, Port, <<"ipv6-multicast">>),
    {ok, {_, _, <<"ipv6-multicast">>}} = gen_udp:recv(Receiver, 14, 3000),
    ok = gen_udp:close(Sender),
    ok = gen_udp:close(Receiver),
    ok.
