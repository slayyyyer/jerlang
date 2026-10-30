%% A gen_server child for the milestone 6 supervision tree. It is started by
%% the supervisor through start_link/0, registers under its own name, and can
%% be made to die so the restart is observable from the test.
-module(ms6_worker).

-behaviour(gen_server).

-export([start_link/0, ping/0, count/0, crash/0]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

start_link() ->
    gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

ping() ->
    gen_server:call(?MODULE, ping).

count() ->
    gen_server:call(?MODULE, count).

crash() ->
    gen_server:call(?MODULE, crash).

init([]) ->
    {ok, 0}.

handle_call(ping, _From, Count) ->
    {reply, pong, Count + 1};
handle_call(count, _From, Count) ->
    {reply, Count, Count};
handle_call(crash, _From, _Count) ->
    exit(boom).

handle_cast(_Message, Count) ->
    {noreply, Count}.

handle_info(_Message, Count) ->
    {noreply, Count}.

terminate(_Reason, _Count) ->
    ok.
