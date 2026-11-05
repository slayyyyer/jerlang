-module(mixed_peer).

-export([echo/1, boot/1]).

echo(Value) ->
    Value.

%% Started with -run. The argument is the JErlang node to call back.
boot([Local]) ->
    boot(Local);
boot(Local) ->
    Node = case is_atom(Local) of
               true -> Local;
               false -> list_to_atom(Local)
           end,
    io:format("READY ~p~n", [node()]),
    Result = erpc:call(Node, java_caller, echo, [<<"from-otp">>]),
    io:format("OTP_TO_JERLANG ~p~n", [Result]),
    receive after infinity -> ok end.
