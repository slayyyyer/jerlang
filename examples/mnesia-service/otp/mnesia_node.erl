%% The Erlang/OTP node. This file is not used by the JVM service at all: it is
%% compiled and run by the OTP installation, and it owns the table the service
%% reads and writes.
%%
%%   erlc -o otp otp/mnesia_node.erl
%%   erl -sname mnesia_node -setcookie examplecookie -noshell \
%%       -pa otp -eval 'mnesia_node:boot().'
%%
%% The table is ram_copies, so nothing is written to disk and no -mnesia dir is
%% needed. Switch it to {disc_copies, [node()]} and pass -mnesia dir when you
%% want the rows to survive a restart; that is mnesia's configuration, not the
%% JVM service's.
-module(mnesia_node).

-export([boot/0, seed/0]).

boot() ->
    {ok, _Started} = application:ensure_all_started(mnesia),
    case mnesia:create_table(people, [{attributes, [name, age]}, {ram_copies, [node()]}]) of
        {atomic, ok} ->
            io:format("created table people~n", []);
        {aborted, {already_exists, people}} ->
            io:format("table people already exists~n", [])
    end,
    seed(),
    io:format("mnesia node ~p ready~n", [node()]),
    timer:sleep(infinity).

seed() ->
    lists:foreach(
        fun({Name, Age}) -> mnesia:dirty_write({people, Name, Age}) end,
        [{<<"ada">>, 36}, {<<"grace">>, 45}]).
