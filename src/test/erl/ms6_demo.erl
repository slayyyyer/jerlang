%% Drives the supervision tree from the Java test. Everything it calls is
%% ordinary OTP API, so the test exercises the real supervisor and gen_server
%% modules from an OTP installation.
%%
%% The Java test calls each function in a fresh process, so start_tree/0 can
%% not link the tree to itself: a supervisor is terminated by an {'EXIT',
%% Parent, normal} message when the process that start_linked it goes away.
%% The keeper process owns the link and stays alive until stop_tree/0.
-module(ms6_demo).

-export([start_tree/0, ping/0, count/0, crash_worker/0, worker/0,
         supervisor/0, stop_tree/0]).

start_tree() ->
    Caller = self(),
    Keeper = spawn(fun() ->
        {ok, Sup} = ms6_sup:start_link(),
        register(ms6_keeper, self()),
        Caller ! {ms6_sup, Sup},
        receive
            stop ->
                unlink(Sup),
                exit(Sup, shutdown),
                ok
        end
    end),
    receive
        {ms6_sup, Sup} ->
            {ok, Sup}
    after 5000 ->
        exit(Keeper, kill),
        {error, timeout}
    end.

ping() ->
    ms6_worker:ping().

count() ->
    ms6_worker:count().

crash_worker() ->
    Pid = whereis(ms6_worker),
    exit(Pid, kill),
    Pid.

worker() ->
    whereis(ms6_worker).

supervisor() ->
    whereis(ms6_sup).

stop_tree() ->
    case whereis(ms6_keeper) of
        undefined ->
            ok;
        Keeper ->
            %% A supervisor traps exits, so exit(Sup, shutdown) would only
            %% arrive as a message. The system protocol is what actually
            %% makes it terminate its children.
            case whereis(ms6_sup) of
                undefined ->
                    ok;
                Sup ->
                    try sys:terminate(Sup, shutdown, 5000) catch _:_ -> ok end
            end,
            unlink(Keeper),
            exit(Keeper, kill),
            ok
    end.
