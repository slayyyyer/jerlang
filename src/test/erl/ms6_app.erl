%% The application callback module of the milestone 6 exit test. The
%% supervision tree is the same unmodified OTP supervisor the other tests use.
-module(ms6_app).

-behaviour(application).

-export([start/2, stop/1, start_phase/3]).

start(_StartType, _StartArgs) ->
    ms6_sup:start_link().

%% The resource file declares one start phase. OTP runs it after start/2
%% has returned, so the test can tell that the controller called it.
start_phase(init, _StartType, Args) ->
    persistent_term:put(ms6_start_phase, Args),
    ok.

stop(_State) ->
    ok.
