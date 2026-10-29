%% Exercises remote process spawn and spawn_request from compiled Erlang.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/dist_spawn_demo.erl
-module(dist_spawn_demo).

-export([spawn_remote/1, spawn_unreachable/1, spawn_mfa/4, request_reply/1, request_error/1,
         request_unreachable/1, request_monitored/1, request_linked/1, request_abandon/1,
         monitor_mfa/4, request_local/0]).

-export([sink/0, spawn_notify/0, notify/1, crash/0, wait_forever/0]).

%% Start a process on Node and use the PID that spawn/4 returned.
spawn_remote(Node) ->
    Pid = spawn(Node, dist_spawn_demo, wait_forever, []),
    Pid ! {hello, self()},
    Pid.

%% An unreachable node yields a pid that names no process.
spawn_unreachable(Node) ->
    spawn(Node, dist_spawn_demo, wait_forever, []).

%% spawn/4 for any entry point, so a live OTP node can be asked to run
%% one of its own functions.
spawn_mfa(Node, Module, Function, Args) ->
    spawn(Node, Module, Function, Args).

%% A plain spawn_request, reported with the default spawn_reply message.
request_reply(Node) ->
    ReqId = spawn_request(Node, dist_spawn_demo, wait_forever, [], []),
    receive
        {spawn_reply, ReqId, ok, Pid} -> {ok, ReqId, Pid};
        {spawn_reply, ReqId, error, Reason} -> {error, ReqId, Reason}
    after 3000 ->
        timeout
    end.

%% A failed spawn request does not deliver a link or a monitor.
request_error(Node) ->
    ReqId = spawn_request(Node, dist_spawn_demo, wait_forever, [], [monitor, link]),
    receive
        {spawn_reply, ReqId, ok, Pid} -> {ok, ReqId, Pid};
        {spawn_reply, ReqId, error, Reason} -> {error, ReqId, Reason}
    after 3000 ->
        timeout
    end.

%% The monitor option makes the request reference a monitor reference,
%% for any entry point.
monitor_mfa(Node, Module, Function, Args) ->
    ReqId = spawn_request(Node, Module, Function, Args, [monitor]),
    receive
        {spawn_reply, ReqId, ok, Pid} ->
            receive
                {'DOWN', ReqId, process, Pid, Reason} -> {down, ReqId, Pid, Reason}
            after 3000 ->
                no_down
            end;
        {spawn_reply, ReqId, error, Reason} ->
            {error, ReqId, Reason}
    after 3000 ->
        timeout
    end.

%% The monitor option makes the request reference a monitor reference.
request_monitored(Node) ->
    monitor_mfa(Node, dist_spawn_demo, wait_forever, []).

%% The link option makes the new process linked to the caller.
request_linked(Node) ->
    process_flag(trap_exit, true),
    ReqId = spawn_request(Node, dist_spawn_demo, wait_forever, [], [link]),
    receive
        {spawn_reply, ReqId, ok, Pid} ->
            receive
                {'EXIT', Pid, Reason} -> {exit, ReqId, Pid, Reason}
            after 3000 ->
                no_exit
            end;
        {spawn_reply, ReqId, error, Reason} ->
            {error, ReqId, Reason}
    after 3000 ->
        timeout
    end.

%% A request to a node that can not be reached fails immediately.
request_unreachable(Node) ->
    ReqId = spawn_request(Node, dist_spawn_demo, wait_forever, [], []),
    receive
        {spawn_reply, ReqId, error, Reason} -> {error, Reason};
        {spawn_reply, ReqId, ok, Pid} -> {ok, Pid}
    after 3000 ->
        timeout
    end.

%% An abandoned request is forgotten, and its late reply is dropped.
request_abandon(Node) ->
    ReqId = spawn_request(Node, dist_spawn_demo, wait_forever, [], []),
    First = spawn_request_abandon(ReqId),
    Second = spawn_request_abandon(ReqId),
    Reply = receive
        {spawn_reply, ReqId, _, _} -> got_reply
    after 500 ->
        no_reply
    end,
    {First, Second, Reply}.

%% A request to the local node takes the same path, without distribution.
request_local() ->
    ReqId = spawn_request(node(), dist_spawn_demo, notify, [self()], [monitor]),
    Reply = receive
        {spawn_reply, ReqId, ok, Pid} -> {ok, Pid};
        {spawn_reply, ReqId, error, Reason} -> {error, Reason}
    after 3000 ->
        timeout
    end,
    Notice = receive
        {notified, Child} -> {notified, Child}
    after 3000 ->
        no_notice
    end,
    Down = receive
        {'DOWN', ReqId, process, Pid2, normal} -> {down, Pid2, normal}
    after 3000 ->
        no_down
    end,
    {Reply, Notice, Down}.

notify(Pid) ->
    Pid ! {notified, self()}.

%% A process that waits to be told who was spawned on this node.
sink() ->
    true = register(spawn_test_sink, self()),
    receive
        {spawned, Pid} -> Pid
    after 5000 ->
        timeout
    end.

%% An entry point for a process spawned by a peer node.
spawn_notify() ->
    case whereis(spawn_test_sink) of
        undefined -> ok;
        Pid -> Pid ! {spawned, self()}
    end,
    wait_forever().

crash() ->
    exit(boom).

wait_forever() ->
    receive
        never -> ok
    end.
