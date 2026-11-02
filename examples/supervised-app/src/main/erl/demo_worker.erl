%% A gen_server that holds one number, so a restart is visible: the new process
%% starts at zero again.
-module(demo_worker).
-behaviour(gen_server).

-export([start_link/0, bump/0, count/0, crash/0]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2, code_change/3]).

start_link() ->
    gen_server:start_link({local, ?MODULE}, ?MODULE, [], []).

bump() ->
    gen_server:call(?MODULE, bump).

count() ->
    gen_server:call(?MODULE, count).

crash() ->
    gen_server:cast(?MODULE, crash).

init([]) ->
    {ok, 0}.

handle_call(bump, _From, Count) ->
    {reply, Count + 1, Count + 1};
handle_call(count, _From, Count) ->
    {reply, Count, Count}.

handle_cast(crash, _Count) ->
    exit(simulated_crash);
handle_cast(_Other, Count) ->
    {noreply, Count}.

handle_info(_Info, Count) ->
    {noreply, Count}.

terminate(_Reason, _Count) ->
    ok.

code_change(_OldVsn, Count, _Extra) ->
    {ok, Count}.
