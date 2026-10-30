package org.jerlang.kernel;

import org.jerlang.kernel.application_controller.ApplicationControllerGetAllEnv;
import org.jerlang.kernel.application_controller.ApplicationControllerGetAllKey;
import org.jerlang.kernel.application_controller.ApplicationControllerGetApplication;
import org.jerlang.kernel.application_controller.ApplicationControllerGetApplicationModule;
import org.jerlang.kernel.application_controller.ApplicationControllerGetEnv;
import org.jerlang.kernel.application_controller.ApplicationControllerGetKey;
import org.jerlang.kernel.application_controller.ApplicationControllerGetMaster;
import org.jerlang.kernel.application_controller.ApplicationControllerInfo;
import org.jerlang.kernel.application_controller.ApplicationControllerIsRunning;
import org.jerlang.kernel.application_controller.ApplicationControllerLoadApplication;
import org.jerlang.kernel.application_controller.ApplicationControllerLoadedApplications;
import org.jerlang.kernel.application_controller.ApplicationControllerPermitApplication;
import org.jerlang.kernel.application_controller.ApplicationControllerSetEnv;
import org.jerlang.kernel.application_controller.ApplicationControllerStartApplication;
import org.jerlang.kernel.application_controller.ApplicationControllerStartType;
import org.jerlang.kernel.application_controller.ApplicationControllerStopApplication;
import org.jerlang.kernel.application_controller.ApplicationControllerUnsetEnv;
import org.jerlang.kernel.application_controller.ApplicationControllerUnloadApplication;
import org.jerlang.kernel.application_controller.ApplicationControllerWhichApplications;
import org.jerlang.type.List;
import org.jerlang.type.Term;

/**
 * = application_controller
 *
 * The server side of the application API. OTP's application module is loaded
 * unmodified and calls these functions; the state and the .app parsing live in
 * {@link org.jerlang.kernel.application_controller.Applications}.
 *
 * Application trees are still ordinary OTP supervisors and gen_servers. The
 * controller only starts the callback module and keeps the bookkeeping that
 * application:which_applications/0, stop/1 and the environment functions need.
 *
 * http://www.erlang.org/doc/man/application.html
 */
public class ApplicationController {

    public static Term load_application(Term application) {
        return ApplicationControllerLoadApplication.dispatch(List.of(application));
    }

    public static Term unload_application(Term application) {
        return ApplicationControllerUnloadApplication.dispatch(List.of(application));
    }

    public static Term start_application(Term application, Term type) {
        return ApplicationControllerStartApplication.dispatch(List.of(application, type));
    }

    public static Term stop_application(Term application) {
        return ApplicationControllerStopApplication.dispatch(List.of(application));
    }

    public static Term is_running(Term application) {
        return ApplicationControllerIsRunning.dispatch(List.of(application));
    }

    public static Term which_applications() {
        return ApplicationControllerWhichApplications.dispatch(List.nil);
    }

    public static Term which_applications(Term timeout) {
        return ApplicationControllerWhichApplications.dispatch(List.of(timeout));
    }

    public static Term loaded_applications() {
        return ApplicationControllerLoadedApplications.dispatch(List.nil);
    }

    public static Term get_master(Term application) {
        return ApplicationControllerGetMaster.dispatch(List.of(application));
    }

    public static Term get_application(Term pid) {
        return ApplicationControllerGetApplication.dispatch(List.of(pid));
    }

    public static Term get_application_module(Term module) {
        return ApplicationControllerGetApplicationModule.dispatch(List.of(module));
    }

    public static Term start_type(Term pidOrName) {
        return ApplicationControllerStartType.dispatch(List.of(pidOrName));
    }

    public static Term get_env(Term application, Term key) {
        return ApplicationControllerGetEnv.dispatch(List.of(application, key));
    }

    public static Term get_env(Term application, Term key, Term defaultValue) {
        return ApplicationControllerGetEnv.dispatch(List.of(application, key, defaultValue));
    }

    public static Term get_all_env(Term application) {
        return ApplicationControllerGetAllEnv.dispatch(List.of(application));
    }

    public static Term set_env(Term config, Term options) {
        return ApplicationControllerSetEnv.dispatch(List.of(config, options));
    }

    public static Term set_env(Term application, Term key, Term value) {
        return ApplicationControllerSetEnv.dispatch(List.of(application, key, value));
    }

    public static Term set_env(Term application, Term key, Term value, Term options) {
        return ApplicationControllerSetEnv.dispatch(List.of(application, key, value, options));
    }

    public static Term unset_env(Term application, Term key) {
        return ApplicationControllerUnsetEnv.dispatch(List.of(application, key));
    }

    public static Term unset_env(Term application, Term key, Term options) {
        return ApplicationControllerUnsetEnv.dispatch(List.of(application, key, options));
    }

    public static Term get_key(Term application, Term key) {
        return ApplicationControllerGetKey.dispatch(List.of(application, key));
    }

    public static Term get_all_key(Term application) {
        return ApplicationControllerGetAllKey.dispatch(List.of(application));
    }

    public static Term info() {
        return ApplicationControllerInfo.dispatch(List.nil);
    }

    public static Term permit_application(Term application, Term permitted) {
        return ApplicationControllerPermitApplication.dispatch(List.of(application, permitted));
    }

}
