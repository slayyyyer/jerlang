%% The chat service: routing, framing and JSON. Same shape as the key-value
%% example - the Java side moves bytes, everything else is decided here - with
%% one addition: messages have to reach clients that did not ask for them, so
%% the room processes call back into Java through chat_sink:deliver/2.
%%
%% The contract with ChatServer.java is four binaries in, one tuple out:
%%
%%     method, path, author, body  ->  {Status, ContentType, Body}
%%
%% The author arrives as the X-Author header, which Java reads; Erlang never sees
%% an HTTP header dictionary.
-module(chat).

-export([init/0, handle/4, join/2, leave/2, frame/4]).

init() ->
    rooms:init(),
    ok.

handle(<<"GET">>, <<"/">>, _Author, _Body) ->
    text(200, <<"JErlang chat service\n"
                "  GET  /rooms            rooms that have been used\n"
                "  GET  /history/<room>   the last 50 messages in a room\n"
                "  POST /say/<room>       append the body, authored by X-Author\n"
                "  GET  /events/<room>    server-sent events, one frame per message\n"
                "  GET  /health           liveness\n"
                "  GET  /metrics          runtime counters, served by the JVM side\n"
                "Sending a message creates a room.\n">>);

handle(<<"GET">>, <<"/health">>, _Author, _Body) ->
    text(200, <<"ok\n">>);

handle(<<"GET">>, <<"/rooms">>, _Author, _Body) ->
    json(200, rooms_json(rooms:names()));

handle(<<"GET">>, <<"/history/", Room/binary>>, _Author, _Body) when Room =/= <<>> ->
    json(200, history_json(rooms:history(Room)));

handle(<<"POST">>, <<"/say/", Room/binary>>, Author, Body) when Room =/= <<>> ->
    case Body of
        <<>> ->
            json(400, error_json(<<"empty_message">>, Room));
        _ ->
            say(Room, Author, Body),
            no_content()
    end;

handle(Method, Path, _Author, _Body) ->
    case routable(Path) of
        true -> json(405, error_json(<<"method_not_allowed">>, Method));
        false -> json(404, error_json(<<"no_such_route">>, Path))
    end.

%% Called by the SSE handler before it starts writing, and by its finally block.
%% The connection id is allocated in Java; Erlang only ever passes it back.
join(Room, Conn) ->
    Pid = rooms:get(Room),
    call(Pid, {join, Conn}).

leave(Room, Conn) ->
    Pid = rooms:get(Room),
    call(Pid, {leave, Conn}).

say(Room, Author, Body) ->
    Pid = rooms:get(Room),
    call(Pid, {say, Author, Body}).

call(Pid, Request) ->
    Pid ! {self(), Request},
    receive
        {room, Reply} -> Reply
    after 5000 ->
        exit(room_timeout)
    end.

%% Requests whose path exists but whose method does not: those are 405, not 404.
routable(<<"/say/", _/binary>>) -> true;
routable(<<"/history/", _/binary>>) -> true;
routable(<<"/rooms">>) -> true;
routable(_) -> false.

%% A server-sent events frame. The id is what a client sends back in
%% Last-Event-ID if it reconnects; nothing reads it yet, which is the one place
%% this example stops short of a resumable stream.
frame(Seq, Room, Author, Text) ->
    <<"id: ", (integer_to_binary(Seq))/binary,
      "\nevent: message\ndata: ", (sanitize(Room))/binary,
      "|", (sanitize(Author))/binary,
      "|", (sanitize(Text))/binary, "\n\n">>.

%% SSE data cannot contain a raw newline, so line breaks become spaces.
sanitize(<<>>) ->
    <<>>;
sanitize(<<10, Rest/binary>>) ->
    <<" ", (sanitize(Rest))/binary>>;
sanitize(<<13, Rest/binary>>) ->
    <<" ", (sanitize(Rest))/binary>>;
sanitize(<<Byte, Rest/binary>>) ->
    <<Byte, (sanitize(Rest))/binary>>.

%% Responses.
text(Status, Body) ->
    {Status, <<"text/plain; charset=utf-8">>, Body}.

json(Status, Document) ->
    {Status, <<"application/json">>, <<Document/binary, 10>>}.

no_content() ->
    {204, <<"text/plain; charset=utf-8">>, <<>>}.

%% JSON by hand, as in the key-value example: every value is a string except the
%% message sequence, which is an integer printed straight from integer_to_binary.
rooms_json([]) ->
    <<"[]">>;
rooms_json(Names) ->
    <<"[", (join_rooms(Names))/binary, "]">>.

join_rooms([Name]) ->
    <<"{\"room\":\"", (escape(Name))/binary, "\"}">>;
join_rooms([Name | Rest]) ->
    <<"{\"room\":\"", (escape(Name))/binary, "\"},", (join_rooms(Rest))/binary>>.

history_json([]) ->
    <<"[]">>;
history_json(Messages) ->
    <<"[", (join_messages(Messages))/binary, "]">>.

join_messages([{Seq, Author, Text}]) ->
    message_json(Seq, Author, Text);
join_messages([{Seq, Author, Text} | Rest]) ->
    <<(message_json(Seq, Author, Text))/binary, ",", (join_messages(Rest))/binary>>.

message_json(Seq, Author, Text) ->
    <<"{\"seq\":", (integer_to_binary(Seq))/binary,
      ",\"author\":\"", (escape(Author))/binary,
      "\",\"text\":\"", (escape(Text))/binary, "\"}">>.

error_json(Kind, Detail) ->
    <<"{\"error\":\"", (escape(Kind))/binary,
      "\",\"detail\":\"", (escape(Detail))/binary, "\"}">>.

%% Byte values rather than $ escapes, so the JSON that comes out is obvious:
%% 34 is the double quote, 92 a backslash, 10/13/9 newline, carriage return, tab.
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
