%% Concurrency measurements shared by JErlang and BEAM.
%%
%% Both functions use only ordinary Erlang processes, messages and receives.
%% There is no Java-specific code here: the same .erl source is compiled by
%% erlc for the BEAM column and by JErlang for the JVM column.
-module(concurrency_demo).

-export([spawn_count/1, spawn_million/0, ring/2]).

%% Spawn N processes that each send one message and exit. Waiting for all N
%% messages is part of the measurement: spawning without observing completion
%% would turn a process benchmark into a loop that can finish before the work.
spawn_count(N) when is_integer(N), N >= 0 ->
    Parent = self(),
    spawn_many(N, Parent),
    wait_done(N).

spawn_million() ->
    spawn_count(1000000).

spawn_many(0, _Parent) ->
    ok;
spawn_many(N, Parent) ->
    spawn(fun() -> Parent ! done end),
    spawn_many(N - 1, Parent).

wait_done(0) ->
    0;
wait_done(N) ->
    receive
        done -> wait_done(N - 1)
    after 30000 ->
        exit({timeout_waiting_for_children, N})
    end.

%% Build a ring of N processes, then pass one token around it Messages times.
%% Every process acknowledges that it has its next pointer before the token is
%% sent, so the token can not overtake ring setup.
ring(N, Messages) when is_integer(N), N >= 1, is_integer(Messages), Messages >= 0 ->
    Parent = self(),
    First = spawn(fun() -> ring_wait(Parent) end),
    Last = build_ring(N - 1, First, Parent),
    First ! {next, Last},
    wait_ready(N, Parent),
    First ! {token, Messages},
    receive
        {ring_done, 0} -> Messages
    after 60000 ->
        exit({ring_timeout, N, Messages})
    end.

build_ring(0, Last, _Parent) ->
    Last;
build_ring(N, Next, Parent) ->
    Pid = spawn(fun() -> ring_wait(Parent) end),
    Pid ! {next, Next},
    build_ring(N - 1, Pid, Parent).

wait_ready(0, _Parent) ->
    ok;
wait_ready(N, Parent) ->
    receive
        {ready, _Pid} -> wait_ready(N - 1, Parent)
    after 30000 ->
        exit({timeout_waiting_for_ring_setup, N})
    end.

ring_wait(Parent) ->
    receive
        {next, Next} ->
            Parent ! {ready, self()},
            ring_loop(Parent, Next)
    end.

ring_loop(Parent, Next) ->
    receive
        {token, 0} ->
            Parent ! {ring_done, 0};
        {token, N} ->
            Next ! {token, N - 1},
            ring_loop(Parent, Next)
    end.
