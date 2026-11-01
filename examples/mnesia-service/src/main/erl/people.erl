%% The service. Every route, status code and body is decided here.
%%
%% The difference from http-service and chat-service is where the data lives: not
%% in this node, but in mnesia on an Erlang/OTP node, reached with rpc:call/5.
%% This node is a JVM that speaks the distribution protocol. It never starts
%% mnesia, and it does not need mnesia's modules on its own code path.
%%
%% The peer arrives as an argument on every call, because a peer is
%% configuration, not state. rpc:call/5 has a timeout, so an unreachable node
%% produces a 503 instead of a request that hangs until the client gives up.
-module(people).

-export([handle/4]).

-define(TIMEOUT, 5000).

handle(<<"GET">>, <<"/">>, _Peer, _Body) ->
    text(200, <<"JErlang + mnesia service\n"
                "  GET    /people        every row as JSON\n"
                "  GET    /people/<name> one row\n"
                "  PUT    /people/<name> write the age in the body\n"
                "  DELETE /people/<name> remove a row\n"
                "  GET    /health        whether mnesia on the peer answers\n"
                "  GET    /metrics       runtime counters, served by the JVM side\n">>);

handle(<<"GET">>, <<"/health">>, Peer, _Body) ->
    case call(Peer, net_adm, ping, [Peer]) of
        pong -> json(200, <<"{\"mnesia\":\"up\"}">>);
        _Other -> down()
    end;

handle(<<"GET">>, <<"/people">>, Peer, _Body) ->
    case call(Peer, mnesia, dirty_match_object, [{people, '_', '_'}]) of
        {down, _Reason} -> down();
        Rows -> json(200, rows_json(Rows))
    end;

handle(<<"GET">>, <<"/people/", Name/binary>>, Peer, _Body) when Name =/= <<>> ->
    read_back(Peer, Name);

handle(<<"PUT">>, <<"/people/", Name/binary>>, Peer, Body) when Name =/= <<>> ->
    case to_integer(Body) of
        {ok, Age} ->
            case call(Peer, mnesia, dirty_write, [{people, Name, Age}]) of
                {down, _Reason} -> down();
                ok -> read_back(Peer, Name)
            end;
        error ->
            json(400, error_json(<<"invalid_age">>, Body))
    end;

handle(<<"DELETE">>, <<"/people/", Name/binary>>, Peer, _Body) when Name =/= <<>> ->
    %% A delete that reports 404 has to look first: mnesia:dirty_delete/1
    %% answers ok whether or not the key was there.
    case call(Peer, mnesia, dirty_read, [people, Name]) of
        {down, _Reason} -> down();
        [] -> json(404, error_json(<<"not_found">>, Name));
        [_Row] ->
            case call(Peer, mnesia, dirty_delete, [{people, Name}]) of
                {down, _Reason} -> down();
                ok -> no_content()
            end
    end;

handle(<<"GET">>, Path, _Peer, _Body) ->
    json(404, error_json(<<"no_such_route">>, Path));

handle(Method, _Path, _Peer, _Body) ->
    json(405, error_json(<<"method_not_allowed">>, Method)).

%% A write is confirmed by reading the row back, so a PUT and the GET after it
%% return byte-identical JSON.
%%
%% It also avoids building a response from a binary carved out of the request
%% path: that shape came back as {"name":"","age":36} in 1.0.0, while the list
%% route — which uses the name mnesia returned — was correct. The cause is not
%% established, so the value that is known to work is the one used here.
read_back(Peer, Name) ->
    case call(Peer, mnesia, dirty_read, [people, Name]) of
        {down, _Reason} -> down();
        [{people, Stored, Age}] -> json(200, row_json(Stored, Age));
        [] -> json(404, error_json(<<"not_found">>, Name))
    end.

%% One place where a dead peer is turned into a value: rpc:call returns
%% {badrpc, Reason} rather than raising, and every route treats it the same way.
call(Peer, Module, Function, Args) ->
    case rpc:call(Peer, Module, Function, Args, ?TIMEOUT) of
        {badrpc, Reason} -> {down, Reason};
        Result -> Result
    end.

down() ->
    json(503, <<"{\"mnesia\":\"down\"}">>).

%% Responses.
text(Status, Body) ->
    {Status, <<"text/plain; charset=utf-8">>, Body}.

json(Status, Document) ->
    {Status, <<"application/json">>, <<Document/binary, 10>>}.

no_content() ->
    {204, <<"text/plain; charset=utf-8">>, <<>>}.

%% JSON, by hand: every value here is either a binary the row already carried or
%% an integer, so the encoder is escaping plus one integer printer.
rows_json([]) ->
    <<"[]">>;
rows_json(Rows) ->
    <<"[", (join_rows(Rows))/binary, "]">>.

join_rows([Row]) ->
    row_of(Row);
join_rows([Row | Rest]) ->
    <<(row_of(Row))/binary, ",", (join_rows(Rest))/binary>>.

row_of({people, Name, Age}) ->
    row_json(Name, Age).

row_json(Name, Age) ->
    <<"{\"name\":\"", (escape(Name))/binary, "\",\"age\":", (int_json(Age))/binary, "}">>.

error_json(Kind, Detail) ->
    <<"{\"error\":\"", (escape(Kind))/binary,
      "\",\"detail\":\"", (escape(Detail))/binary, "\"}">>.

%% There is no binary_to_integer/1 in 1.0.0, so digits are folded by hand and a
%% negative or non-numeric body is rejected rather than stored.
to_integer(<<>>) ->
    error;
to_integer(Bin) ->
    digits(Bin, 0, 0).

digits(<<>>, Accumulator, Count) when Count > 0 ->
    {ok, Accumulator};
digits(<<>>, _Accumulator, 0) ->
    error;
digits(<<Digit, Rest/binary>>, Accumulator, Count) when Digit >= 48, Digit =< 57 ->
    digits(Rest, Accumulator * 10 + (Digit - 48), Count + 1);
digits(_Other, _Accumulator, _Count) ->
    error.

%% Integers are printed by recursion, not by appending to an accumulator: a
%% binary built up in a loop fails at run time in 1.0.0 with
%% "Unsupported bs_create_bin segment type: private_append".
int_json(Digit) when Digit < 10 ->
    <<(48 + Digit)>>;
int_json(Number) ->
    <<(int_json(Number div 10))/binary, (48 + (Number rem 10))>>.

%% Byte values are written out rather than as $ escapes so the JSON that comes
%% out is obvious: 34 is the double quote, 92 a backslash, 10/13/9 newline,
%% carriage return and tab.
escape(<<>>) ->
    <<>>;
escape(<<34, Rest/binary>>) ->
    <<"\\\"", (escape(Rest))/binary>>;
escape(<<92, Rest/binary>>) ->
    <<"\\\\", (escape(Rest))/binary>>;
escape(<<10, Rest/binary>>) ->
    <<"\\n", (escape(Rest))/binary>>;
escape(<<13, Rest/binary>>) ->
    <<"\\r", (escape(Rest))/binary>>;
escape(<<9, Rest/binary>>) ->
    <<"\\t", (escape(Rest))/binary>>;
escape(<<Control, Rest/binary>>) when Control < 32 ->
    escape(Rest);
escape(<<Byte, Rest/binary>>) ->
    <<Byte, (escape(Rest))/binary>>.
