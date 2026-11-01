%% The room registry, and the owner of the message history.
%%
%% This process exists for two reasons:
%%
%%   * it maps a room name to the process that owns the room, with the same
%%     head-pattern lookup store.erl uses, because map lookups compare binary
%%     keys by identity in this runtime;
%%   * it owns the ETS table, so message history lives in the table rather than
%%     in a process. A room process can then die without the history going with
%%     it - which matters here because there is no OTP supervisor to restart it.
%%
%% ets:update_counter/3 is not implemented in 1.0.0 (it raises badarg), so
%% sequence numbers come from the room process itself and this table is only
%% ever appended to.
-module(rooms).

-export([init/0, get/1, names/0, history/1]).

-define(SERVER, rooms).
-define(HISTORY, chat_history).
-define(KEEP, 50).

init() ->
    case whereis(?SERVER) of
        undefined ->
            Parent = self(),
            Pid = spawn(fun() -> start(Parent) end),
            register(?SERVER, Pid),
            %% Answer only once the table exists, so no room can insert first.
            receive
                ready -> ok
            after 5000 ->
                exit(rooms_start_timeout)
            end;
        _Pid ->
            ok
    end.

start(Parent) ->
    ets:new(?HISTORY, [named_table, public, ordered_set]),
    Parent ! ready,
    loop([]).

%% The pid of the room, starting it if this is the first message.
get(Room) ->
    ?SERVER ! {self(), {get, Room}},
    receive
        {rooms, Pid} -> Pid
    after 5000 ->
        exit(rooms_timeout)
    end.

names() ->
    ?SERVER ! {self(), names},
    receive
        {rooms, Names} -> Names
    after 5000 ->
        exit(rooms_timeout)
    end.

%% The last ?KEEP messages of a room, oldest first. The table is ordered by
%% {Room, Seq}, so a room's entries already come out in sequence order and only
%% the tail has to be kept.
history(Room) ->
    last_n(pick(Room, ets:tab2list(?HISTORY)), ?KEEP).

loop(Entries) ->
    receive
        {From, {get, Room}} ->
            case find(Room, Entries) of
                {ok, Pid} ->
                    From ! {rooms, Pid},
                    loop(Entries);
                error ->
                    Pid = room:start(Room),
                    From ! {rooms, Pid},
                    loop([{Room, Pid} | Entries])
            end;
        {From, names} ->
            From ! {rooms, names(Entries)},
            loop(Entries)
    end.

find(Room, [{Room, Pid} | _Rest]) -> {ok, Pid};
find(Room, [_Other | Rest]) -> find(Room, Rest);
find(_Room, []) -> error.

names([{Room, _Pid} | Rest]) -> [Room | names(Rest)];
names([]) -> [].

pick(Room, [{{Room, Seq}, Author, Text} | Rest]) ->
    [{Seq, Author, Text} | pick(Room, Rest)];
pick(Room, [_Other | Rest]) ->
    pick(Room, Rest);
pick(_Room, []) ->
    [].

last_n(List, Max) ->
    case length(List) > Max of
        true -> drop(length(List) - Max, List);
        false -> List
    end.

drop(0, List) -> List;
drop(N, [_First | Rest]) -> drop(N - 1, Rest).
