%% Exercises links, monitors and exit signals across a node boundary.
%%
%% The functions take a PID or a {Name, Node} on the peer node. They are
%% run against a fake distribution peer, so no real Erlang node is needed.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/dist_signals_demo.erl
-module(dist_signals_demo).

-export([link_remote/1, unlink_remote/1, monitor_remote/1, demonitor_remote/1,
         demonitor_then_wait/1, monitor_remote_name/1, remote_exit/1, trapped_link/1,
         link_unreachable/1, noconnection_link/1, noconnection_monitor/1,
         spawn_waiter/0, wait_for_go/0, spawn_crasher/0, crash_after_go/0,
         monitor_nodes_wait/1, monitor_nodes_map/1, monitor_nodes_list/1,
         monitor_nodes_hidden/1, monitor_nodes_invalid/0, monitor_nodes_unsubscribe/0,
         monitor_node_wait/2, monitor_node_twice_wait/2,
         monitor_node_missing/1, nodes_subset/0, is_alive/0, connect_node/1,
         rpc_call/4, rpc_call/5, rpc_cast/4, rpc_target/0,
         rpc_cast_target/0, remote_fun_spawn/2, make_notify_fun/1]).

%% link/1 sends a LINK signal and stays linked until the peer exits.
link_remote(Remote) ->
    true = link(Remote),
    ok.

%% unlink/1 sends UNLINK_ID; the peer acknowledges it. A second unlink has
%% to stay silent, because the link information is already inactive.
unlink_remote(Remote) ->
    true = link(Remote),
    true = unlink(Remote),
    true = unlink(Remote),
    ok.

%% A DOWN message from a remote monitor carries the peer's reason.
monitor_remote(Remote) ->
    Ref = monitor(process, Remote),
    receive
        {'DOWN', Ref, process, Remote, boom} -> ok
    after 3000 ->
        error(no_down)
    end.

demonitor_remote(Remote) ->
    Ref = monitor(process, Remote),
    true = demonitor(Ref),
    ok.

%% A MONITOR_P_EXIT that arrives after demonitor must not deliver DOWN.
demonitor_then_wait(Remote) ->
    Ref = monitor(process, Remote),
    true = demonitor(Ref),
    receive
        {'DOWN', Ref, _, _, _} -> error(down_after_demonitor)
    after 1000 ->
        ok
    end.

%% Monitoring {Name, Node} reports the same object in DOWN.
monitor_remote_name(NameAndNode) ->
    Ref = monitor(process, NameAndNode),
    receive
        {'DOWN', Ref, process, NameAndNode, noproc} -> ok
    after 3000 ->
        error(no_down)
    end.

%% exit/2 sends EXIT2, not the EXIT of a broken link.
remote_exit(Remote) ->
    true = exit(Remote, die),
    ok.

%% trap_exit turns the peer's EXIT signal into a message.
trapped_link(Remote) ->
    false = process_flag(trap_exit, true),
    true = link(Remote),
    receive
        {'EXIT', Remote, boom} -> ok
    after 3000 ->
        error(no_exit)
    end.

%% Linking to a node that can not be reached gives noconnection.
link_unreachable(Remote) ->
    false = process_flag(trap_exit, true),
    true = link(Remote),
    receive
        {'EXIT', Remote, noconnection} -> ok
    after 3000 ->
        error(no_noconnection)
    end.

%% A connection that goes away fails the link.
noconnection_link(Remote) ->
    false = process_flag(trap_exit, true),
    true = link(Remote),
    receive
        {'EXIT', Remote, noconnection} -> ok
    after 3000 ->
        error(no_noconnection)
    end.

%% A connection that goes away also fails a monitor.
noconnection_monitor(Remote) ->
    Ref = monitor(process, Remote),
    receive
        {'DOWN', Ref, process, Remote, noconnection} -> ok
    after 3000 ->
        error(no_down)
    end.

%% A linked process that the test drives from the outside: the peer links
%% or monitors it, the test sends `go`, and the process crashes with boom.
spawn_waiter() ->
    spawn(dist_signals_demo, wait_for_go, []).

wait_for_go() ->
    receive
        go -> exit(boom)
    end.

%% A process for a peer to monitor; it waits until the test lets it crash
%% with an uncaught error, so its exit reason must carry the stacktrace.
spawn_crasher() ->
    spawn(dist_signals_demo, crash_after_go, []).

crash_after_go() ->
    receive
        go -> erlang:error(remote_boom)
    end.

%% Node monitoring. The ready message lets a test connect or disconnect
%% only after the subscription exists.
monitor_nodes_wait(Ready) ->
    ok = net_kernel:monitor_nodes(true),
    Ready ! {ready, self()},
    receive
        {nodeup, Node} -> {nodeup, Node};
        {nodedown, Node} -> {nodedown, Node}
    after 5000 ->
        timeout
    end.

%% A map subscription asks for the three-element form. Wait for both
%% events so the test can inspect the connection id, node type and reason.
monitor_nodes_map(Ready) ->
    ok = net_kernel:monitor_nodes(true, #{connection_id => true,
                                         node_type => all,
                                         nodedown_reason => true}),
    Ready ! {ready, self()},
    receive
        {nodeup, _, _} = Up ->
            receive
                {nodedown, _, _} = Down -> {Up, Down}
            after 5000 ->
                {Up, timeout}
            end
    after 5000 ->
        timeout
    end.

%% A list subscription keeps the info in list form.
monitor_nodes_list(Ready) ->
    ok = net_kernel:monitor_nodes(true, [connection_id, {node_type, all},
                                         nodedown_reason]),
    Ready ! {ready, self()},
    receive
        {nodeup, _, _} = Up -> Up
    after 5000 ->
        timeout
    end.

%% Only hidden connections should reach this subscription.
monitor_nodes_hidden(Ready) ->
    ok = net_kernel:monitor_nodes(true, #{node_type => hidden}),
    Ready ! {ready, self()},
    receive
        {nodeup, _, _} = Up -> Up
    after 5000 ->
        timeout
    end.

monitor_nodes_invalid() ->
    net_kernel:monitor_nodes(true, #{bogus => true}).

monitor_nodes_unsubscribe() ->
    ok = net_kernel:monitor_nodes(true, #{connection_id => true}),
    ok = net_kernel:monitor_nodes(false, #{connection_id => true}),
    ok.

monitor_node_wait(Node, Ready) ->
    true = monitor_node(Node, true),
    Ready ! {ready, self()},
    receive
        {nodeup, Seen} -> {nodeup, Seen};
        {nodedown, Seen} -> {nodedown, Seen}
    after 5000 ->
        timeout
    end.

monitor_node_twice_wait(Node, Ready) ->
    true = monitor_node(Node, true),
    true = monitor_node(Node, true),
    Ready ! {ready, self()},
    receive
        {nodedown, First} ->
            receive
                {nodedown, Second} -> {nodedown, First, Second}
            after 5000 -> {one_nodedown, First}
            end
    after 5000 ->
        timeout
    end.

monitor_node_missing(Node) ->
    true = monitor_node(Node, true),
    receive
        {nodedown, Node} -> nodedown
    after 5000 ->
        timeout
    end.

nodes_subset() ->
    {erlang:nodes(connected), erlang:nodes(visible), erlang:nodes(hidden)}.

is_alive() ->
    erlang:is_alive().

connect_node(Node) ->
    net_kernel:connect_node(Node).

%% RPC through the erpc spawn/monitor protocol.
rpc_call(Node, Module, Function, Args) ->
    rpc:call(Node, Module, Function, Args).

rpc_call(Node, Module, Function, Args, Timeout) ->
    rpc:call(Node, Module, Function, Args, Timeout).

rpc_cast(Node, Module, Function, Args) ->
    rpc:cast(Node, Module, Function, Args).

%% An entry point for an incoming remote call.
rpc_target() ->
    42.

%% An entry point for an incoming remote cast. The message proves that
%% execute_cast really ran on this node.
rpc_cast_target() ->
    rpc_sink ! cast_done,
    ok.

%% Send a fun with a free variable to another node. The remote side has
%% to rebuild the lambda from NEW_FUN_EXT before it can run it.
remote_fun_spawn(Node, N) ->
    F = fun(X) -> X + N end,
    spawn_link(Node, F).

%% A zero-arity fun with a captured name, for an incoming spawn request.
make_notify_fun(Name) ->
    fun() -> Name ! fun_ran, ok end.
