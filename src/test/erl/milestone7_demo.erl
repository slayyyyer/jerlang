%% Compiled by OTP 29. Exercises the remaining milestone 0.7 runtime items:
%% timer wheel timing, process_info fields, priority scheduling, tracing,
%% and the spawn/exit and link/monitor stress exit tests.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/milestone7_demo.erl
-module(milestone7_demo).

-export([spawn_cycles/1, timer_bound/1, signals/1, info/0, priority/1,
         trace_calls/0, trace_tail_calls/0, trace_messages/0, target/1]).

%% 10k spawn/monitor/exit cycles. Every child is gone before the caller
%% returns, so the registry must be back to its baseline.
spawn_cycles(N) ->
    Pairs = [spawn_monitor(fun() -> exit(normal) end) || _ <- lists:seq(1, N)],
    wait_down(Pairs).

wait_down([]) ->
    ok;
wait_down([{Pid, Ref} | Rest]) ->
    receive
        {'DOWN', Ref, process, Pid, normal} ->
            wait_down(Rest)
    after 5000 ->
        {timeout, Pid}
    end.

%% One round of every timer form, with the elapsed milliseconds for each.
%% The Java test checks both the lower bound (it did wait) and an upper bound
%% (the wheel actually woke the process, it did not wait for a poll).
timer_bound(Millis) ->
    T0 = erlang:monotonic_time(millisecond),
    TRef = erlang:start_timer(Millis, self(), start_tick),
    receive
        {timeout, TRef, start_tick} -> ok
    after 2000 ->
        start_timer_timeout
    end,
    T1 = erlang:monotonic_time(millisecond),
    _SRef = erlang:send_after(Millis, self(), send_tick),
    receive
        send_tick -> ok
    after 2000 ->
        send_after_timeout
    end,
    T2 = erlang:monotonic_time(millisecond),
    _ = timer:send_after(Millis, self(), timer_tick),
    receive
        timer_tick -> ok
    after 2000 ->
        timer_send_after_timeout
    end,
    T3 = erlang:monotonic_time(millisecond),
    receive after Millis -> ok end,
    T4 = erlang:monotonic_time(millisecond),
    {T1 - T0, T2 - T1, T3 - T2, T4 - T3}.

%% Link and monitor stress: every worker is both linked and monitored, and
%% the parent traps exits, so one normal stop must produce one EXIT and one
%% DOWN message for every worker.
signals(N) ->
    false = process_flag(trap_exit, true),
    Pids = [spawn_link(fun() -> receive stop -> ok end end) || _ <- lists:seq(1, N)],
    _Refs = [erlang:monitor(process, Pid) || Pid <- Pids],
    [Pid ! stop || Pid <- Pids],
    Result = collect_signals(N, 0, 0),
    true = process_flag(trap_exit, false),
    Result.

collect_signals(N, N, N) ->
    {ok, N, N};
collect_signals(N, Exits, Downs) ->
    receive
        {'EXIT', _Pid, normal} ->
            collect_signals(N, Exits + 1, Downs);
        {'DOWN', _Ref, process, _Pid, normal} ->
            collect_signals(N, Exits, Downs + 1)
    after 5000 ->
        {timeout, Exits, Downs}
    end.

%% The fields added in 0.7 plus the ones that were already reported.
info() ->
    {memory, Memory} = erlang:process_info(self(), memory),
    {max_heap_size, #{size := 0, kill := true, error_logger := true}} =
        erlang:process_info(self(), max_heap_size),
    #{size := 0} = process_flag(max_heap_size,
        #{size => 128, kill => false, error_logger => false}),
    {max_heap_size, #{size := 128, kill := false}} =
        erlang:process_info(self(), max_heap_size),
    {message_queue_data, on_heap} = erlang:process_info(self(), message_queue_data),
    on_heap = process_flag(message_queue_data, off_heap),
    {message_queue_data, QueueData} = erlang:process_info(self(), message_queue_data),
    {status, Status} = erlang:process_info(self(), status),
    {current_function, Current} = erlang:process_info(self(), current_function),
    {message_queue_len, QueueLen} = erlang:process_info(self(), message_queue_len),
    {Memory > 0, QueueData, Status, Current, QueueLen}.

%% Normal children are queued first, then one high-priority child. On a
%% single scheduler a FIFO queue would run a normal child first; the high
%% priority one has to be first.
priority(N) ->
    Parent = self(),
    [spawn(fun() -> Parent ! {done, Index} end) || Index <- lists:seq(1, N)],
    spawn_opt(fun() -> Parent ! {done, high} end, [{priority, high}]),
    collect_priority(N + 1, []).

collect_priority(0, Acc) ->
    lists:reverse(Acc);
collect_priority(N, Acc) ->
    receive
        {done, Who} ->
            collect_priority(N - 1, [Who | Acc])
    after 5000 ->
        {timeout, Acc}
    end.

%% A traced local call. The target is called non-tail, then tracing is
%% switched off before the mailbox is drained.
trace_calls() ->
    1 = erlang:trace(self(), true, [call]),
    1 = erlang:trace_pattern({?MODULE, target, 1}, true, [local]),
    _ = target(7),
    1 = erlang:trace(self(), false, [call]),
    1 = erlang:trace_pattern({?MODULE, target, 1}, false, [local]),
    drain_calls([]).

drain_calls(Acc) ->
    receive
        {trace, _Pid, call, {?MODULE, target, [7]}} ->
            drain_calls([call | Acc]);
        Message ->
            drain_calls([Message | Acc])
    after 50 ->
        lists:reverse(Acc)
    end.

%% A local tail call (call_last) must produce a call event too.
trace_tail_calls() ->
    1 = erlang:trace(self(), true, [call]),
    1 = erlang:trace_pattern({?MODULE, tail_target, 1}, true, [local]),
    10 = tail_call(),
    1 = erlang:trace(self(), false, [call]),
    1 = erlang:trace_pattern({?MODULE, tail_target, 1}, false, [local]),
    drain_tail_calls([]).

tail_call() ->
    tail_target(9).

tail_target(N) ->
    N + 1.

drain_tail_calls(Acc) ->
    receive
        {trace, _Pid, call, {?MODULE, tail_target, [9]}} ->
            drain_tail_calls([call | Acc]);
        Message ->
            drain_tail_calls([Message | Acc])
    after 50 ->
        lists:reverse(Acc)
    end.

%% Send and receive events for a message sent to self.
trace_messages() ->
    1 = erlang:trace(self(), true, [send, 'receive']),
    self() ! hello,
    receive hello -> ok end,
    1 = erlang:trace(self(), false, [send, 'receive']),
    drain_messages([]).

drain_messages(Acc) ->
    receive
        {trace, _Pid, send, hello, _To} ->
            drain_messages([send | Acc]);
        {trace, _Pid, 'receive', hello} ->
            drain_messages(['receive' | Acc]);
        Message ->
            drain_messages([Message | Acc])
    after 50 ->
        lists:reverse(Acc)
    end.

target(N) ->
    N + 1.
