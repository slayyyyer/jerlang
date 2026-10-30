-module(stdlib_boot_demo).

%% Exercises the runtime support the standard library boot needs: error/3,
%% which carries the caller's arguments into the youngest stack frame, and
%% persistent_term, which is where logger and application keep their
%% configuration.

-export([error3/0, error3_info/0, error3_bad_options/0,
         persistent_term_roundtrip/0, persistent_term_info/0,
         persistent_term_missing/0, process_info_report/0,
         fun_info_report/0]).

%% The argument list replaces the arity of the frame that called error/3.
error3() ->
    try
        erlang:error({tagged, 1}, [alpha, beta], [])
    catch
        error:Reason:Stacktrace ->
            [{stdlib_boot_demo, error3, Args, _Location} | _] = Stacktrace,
            {Reason, Args}
    end.

%% error_info belongs to the exception, not to the reason, and an empty
%% argument list is a frame of its own, not an arity.
error3_info() ->
    try
        erlang:error(informative, [], [{error_info, #{cause => demo}}])
    catch
        error:informative:Stacktrace ->
            [{stdlib_boot_demo, error3_info, [], _Location} | _] = Stacktrace,
            error_info_accepted
    end.

%% Every option but {error_info, Map} is a badarg.
error3_bad_options() ->
    try
        erlang:error(ignored, [], not_a_list),
        no_error
    catch
        error:badarg ->
            badarg
    end.

persistent_term_roundtrip() ->
    true = persistent_term:put({stdlib_boot_demo, counter}, 7),
    true = persistent_term:put({stdlib_boot_demo, counter}, 8),
    8 = persistent_term:get({stdlib_boot_demo, counter}),
    default = persistent_term:get({stdlib_boot_demo, missing}, default),
    true = persistent_term:erase({stdlib_boot_demo, counter}),
    erased = persistent_term:get({stdlib_boot_demo, counter}, erased),
    ok.

persistent_term_info() ->
    true = persistent_term:put({stdlib_boot_demo, info}, value),
    persistent_term:info().

%% proc_lib needs registered_name, dictionary and the item list form.
process_info_report() ->
    Self = self(),
    Nameless = process_info(Self, registered_name),
    {status, Status} = process_info(Self, status),
    {dictionary, Dict} = process_info(Self, dictionary),
    [{status, Status}] = process_info(Self, [status]),
    {Nameless, Status, is_list(Dict)}.

%% proc_lib records this as the child's initial call.
fun_info_report() ->
    erlang:fun_info_mfa(fun(A, B) -> A + B end).

%% A key that was never stored is a badarg, not a default value.
persistent_term_missing() ->
    try
        persistent_term:get({stdlib_boot_demo, never_stored}),
        no_error
    catch
        error:badarg ->
            badarg
    end.
