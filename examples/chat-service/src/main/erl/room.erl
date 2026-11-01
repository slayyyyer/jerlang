%% One process per room.
%%
%% A room is a single process, so ordering is free: joins, leaves and messages
%% arrive in one mailbox and are handled one at a time. Fan-out happens inside
%% the message clause, which is what makes the sequence numbers and the delivery
%% order agree.
%%
%% chat_sink:deliver/2 is the Java function registered by ChatServer: it offers
%% the frame to that connection's queue and returns full when the queue is full.
%% Calling it from here is the push direction: this process runs on a scheduler
%% thread, so the Java side must not block in it.
-module(room).

-export([start/1, loop/3]).

start(Room) ->
    spawn(fun() -> loop(Room, [], 1) end).

loop(Room, Members, Next) ->
    receive
        {From, {join, Conn}} ->
            From ! {room, ok},
            loop(Room, [Conn | without(Conn, Members)], Next);
        {From, {leave, Conn}} ->
            From ! {room, ok},
            loop(Room, without(Conn, Members), Next);
        {From, {say, Author, Text}} ->
            Frame = chat:frame(Next, Room, Author, Text),
            ets:insert(chat_history, {{Room, Next}, Author, Text}),
            From ! {room, {ok, Next}},
            loop(Room, deliver(Members, Frame), Next + 1)
    end.

%% A member whose queue is full is dropped. It reconnects, and its next
%% reconnect is where a Last-Event-ID resume would pick up - see the docs page.
deliver([], _Frame) ->
    [];
deliver([Conn | Rest], Frame) ->
    case chat_sink:deliver(Conn, Frame) of
        full -> deliver(Rest, Frame);
        _Ok -> [Conn | deliver(Rest, Frame)]
    end.

without(_Conn, []) -> [];
without(Conn, [Conn | Rest]) -> without(Conn, Rest);
without(Conn, [Other | Rest]) -> [Other | without(Conn, Rest)].
