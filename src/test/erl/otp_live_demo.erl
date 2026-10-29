%% Exercises rpc, node monitoring and remote funs against a real OTP node.
%%
%% The functions run inside a JErlang process, so the BIFs that need a
%% process context (rpc, monitor_node, spawn/2) work as they do in an
%% application.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/otp_live_demo.erl
%%
%% A peer that can run the fun in spawn_fun_notify/2 must have this
%% module on its code path:
%%   erl -name peer@localhost -setcookie dist-test-cookie -pa src/test/resources ...
-module(otp_live_demo).

-export([rpc_node/1, rpc_add/3, rpc_failure/1, rpc_cast_notify/2,
         monitor_node_result/2, monitor_nodes_result/1, spawn_fun_notify/2,
         spawn_link_fun_notify/2, receive_fun/1, make_fun/1,
         send_fun_and_apply/2]).

%% rpc:call runs erlang:node/0 on the peer.
rpc_node(Node) ->
    rpc:call(Node, erlang, node, []).

%% rpc:call passes arguments to the peer.
rpc_add(Node, A, B) ->
    rpc:call(Node, erlang, '+', [A, B]).

%% A remote exception becomes a badrpc tuple, not a local crash.
rpc_failure(Node) ->
    rpc:call(Node, erlang, error, [boom]).

%% rpc:cast returns true and the peer sends the proof to Target.
rpc_cast_notify(Node, Target) ->
    rpc:cast(Node, erlang, send, [Target, cast_ran]).

%% monitor_node/2 on an already connected node reports nodedown when the
%% connection goes away. Target announces the subscription so the test
%% can drop the connection at the right moment.
monitor_node_result(Node, Target) ->
    true = monitor_node(Node, true),
    Target ! {monitoring, Node},
    receive
        {nodedown, Seen} -> {nodedown, Seen}
    after 5000 ->
        timeout
    end.

%% net_kernel:monitor_nodes/1 reports a connection that is established
%% after the subscription. Target announces the subscription.
monitor_nodes_result(Target) ->
    ok = net_kernel:monitor_nodes(true),
    Target ! {monitoring_nodes, node()},
    receive
        {nodeup, Node} -> {nodeup, Node};
        {nodedown, Node} -> {nodedown, Node}
    after 5000 ->
        timeout
    end.

%% Send a fun with a free variable to the peer and have it run there.
spawn_fun_notify(Node, Target) ->
    Pid = spawn(Node, fun() -> Target ! remote_fun_ran end),
    {fun_spawned, Pid}.

%% The peer builds a fun with a free variable and sends it here. Applying
%% it proves that NEW_FUN_EXT decoding rebuilds the lambda and its captured
%% value.
receive_fun(Node) ->
    F = rpc:call(Node, otp_live_demo, make_fun, [42]),
    case is_function(F) of
        true -> {applied, F()};
        false -> {unexpected, F}
    end.

make_fun(N) ->
    fun() -> N end.

%% Send our own fun to the peer and apply it there, so a bad encoding
%% fails here instead of inside a spawned process.
send_fun_and_apply(Node, N) ->
    F = fun() -> N end,
    rpc:call(Node, erlang, apply, [F, []]).

%% The linked variant reports the exit signal the remote fun causes.
spawn_link_fun_notify(Node, Target) ->
    process_flag(trap_exit, true),
    Pid = spawn_link(Node, fun() -> Target ! linked_fun_ran end),
    receive
        {'EXIT', Pid, normal} -> {linked_fun_exited, normal}
    after 5000 ->
        {linked_fun_exited, timeout}
    end.
