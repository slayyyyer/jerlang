%% Exercises ETS sets from compiled Erlang.
%%
%% Compile with:
%%   erlc -o src/test/resources src/test/erl/ets_demo.erl
-module(ets_demo).

-export([store_and_lookup/0, overwrite/0, insert_many/0, mixed_keys/0,
         integer_and_float_keys_are_distinct/0, missing_key/0, bad_object/0,
         unknown_table/0, setelement_does_not_change_table/0,
         delete_key/0, delete_missing_key/0, delete_table/0,
         delete_unknown_table/0, tab2list_all/0, tab2list_empty/0,
         tab2list_unknown_table/0, named_table/0, named_table_is_exclusive/0,
         delete_frees_the_name/0, keypos_option/0,
         keypos_short_object_is_badarg/0, invalid_keypos_is_badarg/0,
         update_counter_increments/0, update_counter_default/0,
         update_counter_thresholds/0, update_counter_multiple/0,
         update_counter_keypos/0, update_counter_empty_ops/0,
         update_counter_badarg/0]).

%% A table created with ets:new/2 holds an object after insert, and lookup
%% finds it by its first element.
store_and_lookup() ->
    Tab = ets:new(ets_demo_store, []),
    true = ets:insert(Tab, {answer, 42}),
    [{answer, 42}] = ets:lookup(Tab, answer),
    ok.

%% A second object with the same key replaces the first, so lookup returns
%% one object and not two.
overwrite() ->
    Tab = ets:new(ets_demo_overwrite, []),
    true = ets:insert(Tab, {key, first}),
    true = ets:insert(Tab, {key, second}),
    [{key, second}] = ets:lookup(Tab, key),
    ok.

%% insert/2 takes a list of objects as well as a single one.
insert_many() ->
    Tab = ets:new(ets_demo_many, []),
    true = ets:insert(Tab, [{a, 1}, {b, 2}, {c, 3}]),
    [{a, 1}] = ets:lookup(Tab, a),
    [{b, 2}] = ets:lookup(Tab, b),
    [{c, 3}] = ets:lookup(Tab, c),
    ok.

%% Keys of different types are kept apart. This is the check that the table
%% is keyed by Erlang term order and not by Java object identity.
mixed_keys() ->
    Tab = ets:new(ets_demo_mixed, []),
    true = ets:insert(Tab, [{1, integer_key}, {1.5, float_key},
                            {a, atom_key}, {{k, v}, tuple_key}]),
    [{1, integer_key}] = ets:lookup(Tab, 1),
    [{1.5, float_key}] = ets:lookup(Tab, 1.5),
    [{a, atom_key}] = ets:lookup(Tab, a),
    [{{k, v}, tuple_key}] = ets:lookup(Tab, {k, v}),
    ok.

%% A set compares keys with exact equality, so 1 and 1.0 are two keys. OTP
%% does the same; ordered_set is the type where they collapse.
integer_and_float_keys_are_distinct() ->
    Tab = ets:new(ets_demo_exact, []),
    true = ets:insert(Tab, [{1, integer_key}, {1.0, float_key}]),
    [{1, integer_key}] = ets:lookup(Tab, 1),
    [{1.0, float_key}] = ets:lookup(Tab, 1.0),
    ok.

%% A key that is not in the table returns an empty list.
missing_key() ->
    Tab = ets:new(ets_demo_missing, []),
    true = ets:insert(Tab, {present, 1}),
    [] = ets:lookup(Tab, absent),
    ok.

%% An object has to be a tuple; anything else is badarg.
bad_object() ->
    Tab = ets:new(ets_demo_bad, []),
    try ets:insert(Tab, not_a_tuple) of
        _ -> error(accepted_bad_object)
    catch
        error:badarg -> ok
    end.

%% A table that does not exist is badarg for both insert and lookup, not a
%% silent no-op or an empty result.
unknown_table() ->
    try ets:insert(no_such_table, {key, value}) of
        _ -> error(inserted_into_missing_table)
    catch
        error:badarg -> ok
    end,
    try ets:lookup(no_such_table, key) of
        _ -> error(looked_up_missing_table)
    catch
        error:badarg -> ok
    end.

%% Inserting stores the object, so a later setelement on the caller's tuple
%% does not change what the table holds.
setelement_does_not_change_table() ->
    Tab = ets:new(ets_demo_alias, []),
    Object = {key, 1},
    true = ets:insert(Tab, Object),
    _ = setelement(2, Object, 2),
    [{key, 1}] = ets:lookup(Tab, key),
    ok.

%% delete/2 removes the object with that key and leaves the others.
delete_key() ->
    Tab = ets:new(ets_demo_delete_key, []),
    true = ets:insert(Tab, [{a, 1}, {b, 2}]),
    true = ets:delete(Tab, a),
    [] = ets:lookup(Tab, a),
    [{b, 2}] = ets:lookup(Tab, b),
    ok.

%% Deleting a key that is not in the table is not an error.
delete_missing_key() ->
    Tab = ets:new(ets_demo_delete_missing, []),
    true = ets:insert(Tab, {a, 1}),
    true = ets:delete(Tab, absent),
    [{a, 1}] = ets:lookup(Tab, a),
    ok.

%% delete/1 removes the table itself, so every later operation on the
%% identifier is badarg, exactly like a table that never existed.
delete_table() ->
    Tab = ets:new(ets_demo_delete_table, []),
    true = ets:insert(Tab, {a, 1}),
    true = ets:delete(Tab),
    try ets:lookup(Tab, a) of
        _ -> error(looked_up_deleted_table)
    catch
        error:badarg -> ok
    end,
    try ets:insert(Tab, {a, 1}) of
        _ -> error(inserted_into_deleted_table)
    catch
        error:badarg -> ok
    end.

%% Deleting a table that does not exist is badarg for both arities.
delete_unknown_table() ->
    try ets:delete(no_such_table) of
        _ -> error(deleted_missing_table)
    catch
        error:badarg -> ok
    end,
    try ets:delete(no_such_table, key) of
        _ -> error(deleted_from_missing_table)
    catch
        error:badarg -> ok
    end.

%% tab2list returns every object. The order is unspecified, so the test
%% checks the members instead of the list.
tab2list_all() ->
    Tab = ets:new(ets_demo_tab2list, []),
    Objects = [{a, 1}, {b, 2}, {c, 3}],
    true = ets:insert(Tab, Objects),
    3 = length(ets:tab2list(Tab)),
    true = all_members(Objects, ets:tab2list(Tab)),
    ok.

tab2list_empty() ->
    Tab = ets:new(ets_demo_tab2list_empty, []),
    [] = ets:tab2list(Tab),
    ok.

tab2list_unknown_table() ->
    try ets:tab2list(no_such_table) of
        _ -> error(tab2list_of_missing_table)
    catch
        error:badarg -> ok
    end.

%% A named table is created and addressed by its name, and ets:new returns
%% that name instead of an identifier.
named_table() ->
    ets_demo_named = ets:new(ets_demo_named, [named_table]),
    true = ets:insert(ets_demo_named, {key, value}),
    [{key, value}] = ets:lookup(ets_demo_named, key),
    true = ets:delete(ets_demo_named, key),
    [] = ets:lookup(ets_demo_named, key),
    ok.

%% A name belongs to one table at a time; a second ets:new is badarg rather
%% than replacing the first table.
named_table_is_exclusive() ->
    ets_demo_exclusive = ets:new(ets_demo_exclusive, [named_table]),
    try ets:new(ets_demo_exclusive, [named_table]) of
        _ -> error(created_second_table_with_same_name)
    catch
        error:badarg -> ok
    end.

%% Deleting a named table frees its name for a new table.
delete_frees_the_name() ->
    ets_demo_freed = ets:new(ets_demo_freed, [named_table]),
    true = ets:delete(ets_demo_freed),
    ets_demo_freed = ets:new(ets_demo_freed, [named_table]),
    ok.

%% {keypos, 2} makes the second element the key, and the first element no
%% longer keys anything.
keypos_option() ->
    Tab = ets:new(ets_demo_keypos, [{keypos, 2}]),
    true = ets:insert(Tab, {ignored, key}),
    [{ignored, key}] = ets:lookup(Tab, key),
    [] = ets:lookup(Tab, ignored),
    ok.

%% An object shorter than the key position is badarg.
keypos_short_object_is_badarg() ->
    Tab = ets:new(ets_demo_keypos_short, [{keypos, 3}]),
    try ets:insert(Tab, {a, b}) of
        _ -> error(inserted_object_shorter_than_keypos)
    catch
        error:badarg -> ok
    end.

%% A key position below 1 is badarg.
invalid_keypos_is_badarg() ->
    try ets:new(ets_demo_bad_keypos, [{keypos, 0}]) of
        _ -> error(accepted_invalid_keypos)
    catch
        error:badarg -> ok
    end.

%% update_counter/3 increments the counter and returns the new value.
update_counter_increments() ->
    Tab = ets:new(ets_demo_counter, []),
    true = ets:insert(Tab, {counter, 1}),
    2 = ets:update_counter(Tab, counter, {2, 1}),
    3 = ets:update_counter(Tab, counter, {2, 1}),
    [{counter, 3}] = ets:lookup(Tab, counter),
    ok.

%% Without a default, a missing key is badarg. With one, the object is
%% created from the default and its key element is set to the requested key,
%% even when the default carries a different key.
update_counter_default() ->
    Tab = ets:new(ets_demo_counter_default, []),
    try ets:update_counter(Tab, counter, {2, 1}) of
        _ -> error(invented_object_without_default)
    catch
        error:badarg -> ok
    end,
    11 = ets:update_counter(Tab, counter, {2, 1}, {counter, 10}),
    [{counter, 11}] = ets:lookup(Tab, counter),
    6 = ets:update_counter(Tab, other, {2, 1}, {wrong_key, 5}),
    [{other, 6}] = ets:lookup(Tab, other),
    [] = ets:lookup(Tab, wrong_key),
    ok.

%% A threshold replaces the result when it is crossed: above it for a
%% positive increment, below it for a negative one.
update_counter_thresholds() ->
    Tab = ets:new(ets_demo_counter_threshold, []),
    true = ets:insert(Tab, {counter, 5}),
    6 = ets:update_counter(Tab, counter, {2, 1, 7, 100}),
    100 = ets:update_counter(Tab, counter, {2, 2, 7, 100}),
    true = ets:insert(Tab, {down, 5}),
    3 = ets:update_counter(Tab, down, {2, -2, 3, 0}),
    0 = ets:update_counter(Tab, down, {2, -1, 3, 0}),
    ok.

%% A list of operations updates all of them and returns the new values in the
%% same order, even for a one-element list.
update_counter_multiple() ->
    Tab = ets:new(ets_demo_counter_multi, []),
    true = ets:insert(Tab, {counter, 1, 2}),
    [2, 4] = ets:update_counter(Tab, counter, [{2, 1}, {3, 2}]),
    [3, 5] = ets:update_counter(Tab, counter, [{2, 1}, {3, 1}]),
    [6] = ets:update_counter(Tab, counter, [{2, 3}]),
    [{counter, 6, 5}] = ets:lookup(Tab, counter),
    ok.

%% The counter position follows {keypos, N}: neither the key position nor
%% position 1 may be updated.
update_counter_keypos() ->
    Tab = ets:new(ets_demo_counter_keypos, [{keypos, 2}]),
    1 = ets:update_counter(Tab, counter, {3, 1}, {ignored, counter, 0}),
    [{ignored, counter, 1}] = ets:lookup(Tab, counter),
    try ets:update_counter(Tab, counter, {2, 1}) of
        _ -> error(updated_the_key_position)
    catch
        error:badarg -> ok
    end,
    try ets:update_counter(Tab, counter, {1, 1}) of
        _ -> error(updated_position_one)
    catch
        error:badarg -> ok
    end.

%% An empty operation list returns [] and only creates the default object.
update_counter_empty_ops() ->
    Tab = ets:new(ets_demo_counter_empty, []),
    true = ets:insert(Tab, {counter, 5}),
    [] = ets:update_counter(Tab, counter, []),
    [{counter, 5}] = ets:lookup(Tab, counter),
    Tab2 = ets:new(ets_demo_counter_empty2, []),
    [] = ets:update_counter(Tab2, counter, [], {counter, 5}),
    [{counter, 5}] = ets:lookup(Tab2, counter),
    try ets:update_counter(Tab2, missing, []) of
        _ -> error(missing_key_with_empty_ops)
    catch
        error:badarg -> ok
    end.

%% Non-integer counters, increments and thresholds, and a default shorter
%% than the updated position, are all badarg.
update_counter_badarg() ->
    Tab = ets:new(ets_demo_counter_bad, []),
    true = ets:insert(Tab, {counter, not_a_number}),
    try ets:update_counter(Tab, counter, {2, 1}) of
        _ -> error(incremented_a_non_number)
    catch
        error:badarg -> ok
    end,
    Tab2 = ets:new(ets_demo_counter_bad2, []),
    true = ets:insert(Tab2, {counter, 1}),
    try ets:update_counter(Tab2, counter, {2, 1.5}) of
        _ -> error(accepted_float_increment)
    catch
        error:badarg -> ok
    end,
    try ets:update_counter(Tab2, counter, {2, 1, 5.0, 0}) of
        _ -> error(accepted_float_threshold)
    catch
        error:badarg -> ok
    end,
    try ets:update_counter(Tab2, counter, {3, 1}) of
        _ -> error(updated_past_the_object)
    catch
        error:badarg -> ok
    end,
    try ets:update_counter(Tab2, missing, {3, 1}, {missing, 10}) of
        _ -> error(default_shorter_than_position)
    catch
        error:badarg -> ok
    end.

all_members([], _) ->
    true;
all_members([Object | Rest], Objects) ->
    member(Object, Objects) andalso all_members(Rest, Objects).

member(_, []) ->
    false;
member(Object, [Object | _]) ->
    true;
member(Object, [_ | Rest]) ->
    member(Object, Rest).
