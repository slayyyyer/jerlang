%% An application included by ms6_app. It has a supervision tree with no
%% children, so the test can tell that the controller started and stopped it
%% with its parent without registering any names.
-module(ms6_included_app).

-behaviour(application).

-export([start/2, stop/1]).

start(_StartType, _StartArgs) ->
    ms6_empty_sup:start_link().

stop(_State) ->
    ok.
