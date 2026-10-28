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
         spawn_waiter/0, wait_for_go/0]).

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
