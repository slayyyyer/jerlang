%% The application callback. OTP's application controller calls start/2 when the
%% application is started and stop/1 when it is stopped.
-module(demo_app).
-behaviour(application).

-export([start/2, stop/1]).

start(_StartType, _StartArgs) ->
    demo_sup:start_link().

stop(_State) ->
    ok.
