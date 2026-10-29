%% Exercises the native gen_tcp and inet modules.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/gen_tcp_demo.erl
-module(gen_tcp_demo).

-export([roundtrip/0, active_roundtrip/0, list_roundtrip/0, timeout_test/0,
         getopts_test/0, address_helpers/0, listen_default/0,
         socket_options/0, half_close/0, send_timeout/0]).

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

%% listen/1 takes the port alone and uses the OTP defaults.
listen_default() ->
    {ok, Listen} = gen_tcp:listen(0),
    {ok, {_, Port}} = inet:sockname(Listen),
    true = Port > 0,
    {ok, [{active, true}, {mode, list}, {packet, 0}]} =
        inet:getopts(Listen, [active, mode, packet]),
    ok = gen_tcp:close(Listen),
    ok.

%% The common socket options round-trip through getopts, and the buffer
%% sizes below the OS minimum are reported as the size actually in use.
socket_options() ->
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false},
                                      {reuseaddr, true}]),
    {ok, {_, Port}} = inet:sockname(Listen),
    {ok, Client} = gen_tcp:connect({127, 0, 0, 1}, Port,
        [binary, {active, false}, {sndbuf, 16384}, {recbuf, 16384},
         {linger, {true, 1}}, {buffer, 4096}, {keepalive, true},
         {exit_on_close, false}]),
    {ok, Server} = gen_tcp:accept(Listen, 2000),
    {ok, [{sndbuf, Snd}, {recbuf, Rcv}, {linger, {true, 1}},
          {buffer, 4096}, {keepalive, true}, {exit_on_close, false}]} =
        inet:getopts(Client, [sndbuf, recbuf, linger, buffer, keepalive,
                              exit_on_close]),
    true = Snd >= 16384,
    true = Rcv >= 16384,
    ok = inet:setopts(Client, [{nodelay, true}, {linger, {true, 2}},
                               {sndbuf, 32768}, {buffer, 8192}]),
    {ok, [{nodelay, true}, {linger, {true, 2}}, {sndbuf, Changed},
          {buffer, 8192}]} =
        inet:getopts(Client, [nodelay, linger, sndbuf, buffer]),
    true = Changed >= 32768,
    ok = gen_tcp:close(Server),
    ok = gen_tcp:close(Client),
    ok = gen_tcp:close(Listen),
    ok.

%% A peer that never reads fills the send buffer. send_timeout bounds the
%% send, and send_timeout_close closes the socket when it fires.
send_timeout() ->
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false},
                                      {recbuf, 4096}]),
    {ok, {_, Port}} = inet:sockname(Listen),
    {ok, Client} = gen_tcp:connect({127, 0, 0, 1}, Port,
        [binary, {active, false}, {sndbuf, 4096},
         {send_timeout, 200}]),
    {ok, Server} = gen_tcp:accept(Listen, 2000),
    {ok, [{send_timeout, 200}]} = inet:getopts(Client, [send_timeout]),
    {ok, []} = inet:getopts(Client, [send_timeout_close]),
    Payload = binary:copy(<<"x">>, 262144),
    timeout = fill_send_buffer(Client, Payload, 100),
    ok = inet:setopts(Client, [{send_timeout_close, true}]),
    {ok, [{send_timeout_close, true}]} =
        inet:getopts(Client, [send_timeout_close]),
    timeout = fill_send_buffer(Client, Payload, 3),
    ok = wait_for_peer_close(Server, 5),
    ok = gen_tcp:close(Client),
    ok = gen_tcp:close(Server),
    ok = gen_tcp:close(Listen),
    ok.

fill_send_buffer(_Socket, _Payload, 0) ->
    no_timeout;
fill_send_buffer(Socket, Payload, N) ->
    case gen_tcp:send(Socket, Payload) of
        ok -> fill_send_buffer(Socket, Payload, N - 1);
        {error, timeout} -> timeout
    end.

%% send_timeout_close closes the socket, so the peer reads end of file.
wait_for_peer_close(_Socket, 0) ->
    error(peer_not_closed);
wait_for_peer_close(Socket, N) ->
    case gen_tcp:recv(Socket, 65536, 2000) of
        {ok, _} -> wait_for_peer_close(Socket, N);
        {error, closed} -> ok;
        {error, timeout} -> wait_for_peer_close(Socket, N - 1)
    end.

%% With exit_on_close false a half-closed peer still receives our writes.
half_close() ->
    {ok, Listen} = gen_tcp:listen(0, [binary, {active, false},
                                      {exit_on_close, false}]),
    {ok, {_, Port}} = inet:sockname(Listen),
    {ok, Client} = gen_tcp:connect({127, 0, 0, 1}, Port,
                                   [binary, {active, false}]),
    {ok, Server} = gen_tcp:accept(Listen, 2000),
    ok = inet:setopts(Server, [{active, true}]),
    ok = gen_tcp:send(Client, <<"hello">>),
    receive
        {tcp, Server, <<"hello">>} -> ok
    after 2000 ->
        error(no_data)
    end,
    ok = gen_tcp:shutdown(Client, write),
    receive
        {tcp_closed, Server} -> ok
    after 2000 ->
        error(no_close)
    end,
    ok = gen_tcp:send(Server, <<"bye">>),
    {ok, <<"bye">>} = gen_tcp:recv(Client, 3, 2000),
    ok = gen_tcp:close(Server),
    ok = gen_tcp:close(Client),
    ok = gen_tcp:close(Listen),
    ok.
