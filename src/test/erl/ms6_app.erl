%% The application callback module of the milestone 6 exit test. The
%% supervision tree is the same unmodified OTP supervisor the other tests use.
-module(ms6_app).

-behaviour(application).

-export([start/2, stop/1]).

start(_StartType, _StartArgs) ->
    ms6_sup:start_link().

stop(_State) ->
    ok.
