{application, ms6_app,
 [{description, "Milestone 6 application exit test"},
  {vsn, "1.0"},
  {modules, [ms6_app, ms6_sup, ms6_worker]},
  {registered, [ms6_sup, ms6_worker]},
  {applications, [kernel, stdlib]},
  {mod, {ms6_app, []}},
  {env, [{restart_intensity, 3}]}]}.
