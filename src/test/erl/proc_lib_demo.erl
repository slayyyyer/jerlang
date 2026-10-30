-module(proc_lib_demo).

%% Runs OTP's own proc_lib against this runtime. The module is compiled by
%% OTP 29 and the runtime loads proc_lib from the OTP installation, so both
%% sides of the call are unmodified OTP code.

-export([spawn_value/0, start_value/0, init/1]).

%% proc_lib:spawn/1 runs the fun in a new process that sends its pid back.
spawn_value() ->
    Parent = self(),
    proc_lib:spawn(fun() -> Parent ! {spawned, self()} end),
    receive
        {spawned, Pid} ->
            {ok, is_pid(Pid)}
    after 5000 ->
        timeout
    end.

%% proc_lib:start/3 waits for the child to call proc_lib:init_ack/2, which is
%% the handshake every OTP behaviour is built on.
start_value() ->
    case proc_lib:start(?MODULE, init, [self()]) of
        {ok, Pid} ->
            Pid ! {ping, self()},
            receive
                {pong, Pid} ->
                    {ok, is_pid(Pid)}
            after 5000 ->
                timeout
            end;
        Other ->
            Other
    end.

init(Parent) ->
    proc_lib:init_ack(Parent, {ok, self()}),
    receive
        {ping, From} ->
            From ! {pong, self()}
    end.
