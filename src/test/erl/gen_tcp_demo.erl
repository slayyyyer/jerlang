%% Exercises the native gen_tcp and inet modules.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/gen_tcp_demo.erl
-module(gen_tcp_demo).

-export([roundtrip/0, active_roundtrip/0, list_roundtrip/0, timeout_test/0,
         getopts_test/0, address_helpers/0]).

roundtrip() ->
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false}, {reuseaddr, true}]),
    {ok, {_, Port}} = inet:sockname(Listen),
    {ok, Port} = inet:port(Listen),
    {ok, Client} = gen_tcp:connect({127, 0, 0, 1}, Port, [binary, {active, false}]),
    {ok, Server} = gen_tcp:accept(Listen, 2000),
    ok = gen_tcp:send(Client, <<"ping">>),
    {ok, <<"ping">>} = gen_tcp:recv(Server, 4, 2000),
    ok = gen_tcp:send(Server, <<"pong">>),
    {ok, <<"pong">>} = gen_tcp:recv(Client, 4, 2000),
    ok = gen_tcp:close(Server),
    ok = gen_tcp:close(Client),
    ok = gen_tcp:close(Listen),
    ok.

active_roundtrip() ->
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, true}]),
    {ok, {_, Port}} = inet:sockname(Listen),
    {ok, Client} = gen_tcp:connect({127, 0, 0, 1}, Port, [binary, {active, true}]),
    {ok, Server} = gen_tcp:accept(Listen, 2000),
    ok = gen_tcp:send(Server, <<"active">>),
    receive
        {tcp, Client, <<"active">>} -> ok
    after 2000 ->
        error(client_no_message)
    end,
    ok = gen_tcp:send(Client, <<"back">>),
    receive
        {tcp, Server, <<"back">>} -> ok
    after 2000 ->
        error(server_no_message)
    end,
    ok = gen_tcp:close(Server),
    ok = gen_tcp:close(Client),
    ok = gen_tcp:close(Listen),
    ok.

list_roundtrip() ->
    {ok, Listen} = gen_tcp:listen(0, [{active, false}]),
    {ok, {_, Port}} = inet:sockname(Listen),
    {ok, Client} = gen_tcp:connect({127, 0, 0, 1}, Port, [{active, false}]),
    {ok, Server} = gen_tcp:accept(Listen, 2000),
    ok = gen_tcp:send(Client, "hello"),
    {ok, "hello"} = gen_tcp:recv(Server, 5, 2000),
    ok = gen_tcp:close(Server),
    ok = gen_tcp:close(Client),
    ok = gen_tcp:close(Listen),
    ok.

timeout_test() ->
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false}]),
    {ok, {_, Port}} = inet:sockname(Listen),
    {ok, Client} = gen_tcp:connect({127, 0, 0, 1}, Port, [binary, {active, false}]),
    {ok, Server} = gen_tcp:accept(Listen, 2000),
    {error, timeout} = gen_tcp:recv(Server, 1, 100),
    ok = gen_tcp:shutdown(Server, read_write),
    ok = gen_tcp:close(Server),
    ok = gen_tcp:close(Client),
    ok = gen_tcp:close(Listen),
    ok.

getopts_test() ->
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false}]),
    {ok, [{active, false}]} = inet:getopts(Listen, [active]),
    ok = inet:setopts(Listen, [{active, true}]),
    {ok, [{active, true}]} = inet:getopts(Listen, [active]),
    ok = gen_tcp:close(Listen),
    ok.

address_helpers() ->
    {ok, {127, 0, 0, 1}} = inet:parse_address("127.0.0.1"),
    "127.0.0.1" = inet:ntoa({127, 0, 0, 1}),
    ok.
