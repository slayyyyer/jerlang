%% The milestone 0.4 acceptance case: a small application that spans two
%% nodes. The coordinator runs on the JErlang node, the job runs on a real
%% Erlang/OTP node, and the two halves talk in both directions.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/acceptance_demo.erl
%%
%% The peer needs this module on its code path because it runs worker/2 and
%% ping_named/1.
-module(acceptance_demo).

-export([run/1, worker/2, ping_named/1]).

%% Start the remote half, collect what it sends, then hand it a fun and let
%% it call back by name. Returns {ok, Worker, Steps, Reason, FunResult, Pong}.
run(Peer) ->
    register(acceptance_sink, self()),
    Target = {acceptance_sink, node()},

    %% 1. Start the worker on the peer and monitor it in one request.
    ReqId = spawn_request(Peer, acceptance_demo, worker, [Target, 3], [monitor]),
    Worker = receive
                 {spawn_reply, ReqId, ok, Pid} -> Pid
             after 10000 ->
                 exit(no_spawn_reply)
             end,
    true = (node(Worker) =:= Peer),

    %% 2. The worker reports each step to the name registered here, so every
    %% message crosses from OTP to this node.
    Steps = collect(3, []),
    {work_done, Sum} = receive
                           {work_done, Value} -> {work_done, Value}
                       after 10000 ->
                           exit(no_work_done)
                       end,

    %% 3. It exits normally, and the monitor reports it.
    Reason = receive
                 {'DOWN', ReqId, process, Worker, Why} -> Why
             after 10000 ->
                 exit(no_down)
             end,

    %% 4. A fun built here runs on the peer with its captured value.
    FunResult = rpc:call(Peer, erlang, apply, [fun() -> lists:sum(Steps) end, []]),

    %% 5. The peer starts a process that answers a name registered here.
    true = rpc:cast(Peer, acceptance_demo, ping_named, [Target]),
    Pong = receive
               pong_from_peer -> pong_from_peer
           after 10000 ->
               timeout
           end,

    {ok, Worker, Steps, Reason, {Sum, FunResult}, Pong}.

%% Runs on the peer.
worker(Target, Steps) ->
    lists:foreach(fun(N) -> Target ! {progress, N * N} end, lists:seq(1, Steps)),
    Target ! {work_done, lists:sum(lists:seq(1, Steps))},
    exit(normal).

%% Runs on the peer, too: it answers a name that is registered on the other
%% node, which is the inbound direction of registration.
ping_named(Target) ->
    Target ! pong_from_peer.

collect(0, Acc) ->
    Acc;
collect(N, Acc) ->
    receive
        {progress, Value} -> collect(N - 1, Acc ++ [Value])
    after 10000 ->
        exit(no_progress)
    end.
