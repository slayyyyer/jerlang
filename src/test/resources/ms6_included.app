{application, ms6_included,
 [{description, "Included application for the milestone 6 exit test"},
  {vsn, "1.0"},
  {modules, [ms6_included_app, ms6_empty_sup]},
  {registered, [ms6_empty_sup]},
  {applications, [kernel, stdlib]},
  {mod, {ms6_included_app, []}}]}.
