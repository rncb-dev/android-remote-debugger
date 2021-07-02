/*
 * Copyright 2020 Arman Sargsyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package zerobranch.androidremotedebugger;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;

import zerobranch.androidremotedebugger.logging.DefaultLogger;
import zerobranch.androidremotedebugger.logging.Logger;
import zerobranch.androidremotedebugger.logging.RemoteLog;
import zerobranch.androidremotedebugger.settings.InternalSettings;
import zerobranch.androidremotedebugger.source.local.LogLevel;
import zerobranch.androidremotedebugger.source.managers.ContinuousDBManager;

public final class AndroidRemoteDebugger {
    private static final int DEFAULT_PORT = 8080;
    private static final int MAX_PORT_VALUE = 8090;
    private final Builder builder;
    private static RemoteLog remoteLog;
    private static AndroidRemoteDebugger instance;
    private static boolean isEnable;
    private static boolean isEnabledNotifications;

    private AndroidRemoteDebugger(Builder builder) {
        this.builder = builder;
    }

    public synchronized static void init(Context context) {
        init(new Builder(context).build());
    }

    public synchronized static void init(final AndroidRemoteDebugger androidRemoteDebugger) {
        if (isNotDefaultProcess(androidRemoteDebugger.builder.context)) {
            return;
        }

        instance = androidRemoteDebugger;
        isEnable = androidRemoteDebugger.builder.enabled;
        isEnabledNotifications = androidRemoteDebugger.builder.enabledNotifications;

        if (!isEnable) {
            stop();
            return;
        }

        if (isAliveWebServer()) {
            return;
        }

        final Builder builder = androidRemoteDebugger.builder;

        if (builder.includedUncaughtException) {
            setUncaughtExceptionHandler();
        }

        InternalSettings internalSettings = new InternalSettings(
                builder.enabledInternalLogging,
                builder.enabledJsonPrettyPrint
        );

        ServerRunner.getInstance().init(builder.context, internalSettings, builder.port, new ServerRunner.ConnectionStatus() {
            @Override
            public void onResult(boolean isSuccessRunning, String ipPort) {
                if (isEnabledNotifications) {
                    AppNotification.init(builder.context);

                    if (isSuccessRunning) {
                        AppNotification.notify("Successfully", String.format("http://%s", ipPort));
                    } else {
                        AppNotification.notifyError("Failed connection", String.format("%s is busy", ipPort));
                    }
                }

                ContinuousDBManager.init(builder.context);
                remoteLog = new RemoteLog(androidRemoteDebugger.builder.logger);
            }
        });
    }

    public synchronized static void stop() {
        isEnable = false;
        remoteLog = null;
        instance = null;
        ServerRunner.stop();
        ContinuousDBManager.destroy();
        AppNotification.destroy();
    }

    public static boolean isEnable() {
        return isEnable;
    }

    public static boolean isAliveWebServer() {
        return ServerRunner.isAlive();
    }

    static void reconnect() {
        reconnect(null);
    }

    static void reconnect(Context context) {
        if (instance == null) {
            if (isEnabledNotifications) {
                AppNotification.cancelAll(context);
            }
            return;
        }

        init(instance);
    }

    static void reconnectWithNewPort() {
        reconnectWithNewPort(null);
    }

    static void reconnectWithNewPort(Context context) {
        if (instance == null) {
            if (isEnabledNotifications) {
                AppNotification.cancelAll(context);
            }
            return;
        }

        int port = instance.builder.port;
        instance.builder.port = port >= MAX_PORT_VALUE ? DEFAULT_PORT : port + 1;
        init(instance);
    }

    private static void setUncaughtExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();

            @Override
            public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
                Log.wtf(e);
                if (originalHandler != null) {
                    originalHandler.uncaughtException(t, e);
                }
            }
        });
    }

    private static boolean isNotDefaultProcess(Context context) {
        String currentProcessName = "";
        int currentPid = android.os.Process.myPid();
        Object objectManager = context.getSystemService(Context.ACTIVITY_SERVICE);

        if (objectManager instanceof ActivityManager) {
            ActivityManager manager = (ActivityManager) objectManager;

            for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
                if (processInfo.pid == currentPid) {
                    currentProcessName = processInfo.processName;
                    break;
                }
            }
            return !currentProcessName.equals(context.getPackageName());
        } else {
            return false;
        }
    }

    public static class Builder {
        private final Context context;
        private boolean enabled = true;
        private boolean enabledInternalLogging = true;
        private boolean enabledJsonPrettyPrint = true;
        private boolean enabledNotifications = true;
        private boolean includedUncaughtException = true;
        private int port = DEFAULT_PORT;
        private Logger logger;

        public Builder(Context context) {
            this.context = context.getApplicationContext();
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder disableInternalLogging() {
            enabledInternalLogging = false;
            return this;
        }

        public Builder enableDuplicateLogging() {
            this.logger = new DefaultLogger();
            return this;
        }

        public Builder enableDuplicateLogging(Logger logger) {
            this.logger = logger;
            return this;
        }

        public Builder disableJsonPrettyPrint() {
            enabledJsonPrettyPrint = false;
            return this;
        }

        public Builder disableNotifications() {
            enabledNotifications = false;
            return this;
        }

        public Builder excludeUncaughtException() {
            includedUncaughtException = false;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public AndroidRemoteDebugger build() {
            return new AndroidRemoteDebugger(this);
        }
    }

    public static class Log {
        public static void v(Throwable th) {
            log(LogLevel.VERBOSE, null, null, th);
        }

        public static void v(String msg) {
            log(LogLevel.VERBOSE, null, msg, null);
        }

        public static void v(String tag, String msg) {
            log(LogLevel.VERBOSE, tag, msg, null);
        }

        public static void v(String tag, String msg, Throwable th) {
            log(LogLevel.VERBOSE, tag, msg, th);
        }

        public static void d(Throwable th) {
            log(LogLevel.DEBUG, null, null, th);
        }

        public static void d(String msg) {
            log(LogLevel.DEBUG, null, msg, null);
        }

        public static void d(String tag, String msg) {
            log(LogLevel.DEBUG, tag, msg, null);
        }

        public static void d(String tag, String msg, Throwable th) {
            log(LogLevel.DEBUG, tag, msg, th);
        }

        public static void i(Throwable th) {
            log(LogLevel.INFO, null, null, th);
        }

        public static void i(String msg) {
            log(LogLevel.INFO, null, msg, null);
        }

        public static void i(String tag, String msg) {
            log(LogLevel.INFO, tag, msg, null);
        }

        public static void i(String tag, String msg, Throwable th) {
            log(LogLevel.INFO, tag, msg, th);
        }

        public static void w(Throwable th) {
            log(LogLevel.WARN, null, null, th);
        }

        public static void w(String msg) {
            log(LogLevel.WARN, null, msg, null);
        }

        public static void w(String tag, String msg) {
            log(LogLevel.WARN, tag, msg, null);
        }

        public static void w(String tag, String msg, Throwable th) {
            log(LogLevel.WARN, tag, msg, th);
        }

        public static void e(Throwable th) {
            log(LogLevel.ERROR, null, null, th);
        }

        public static void e(String msg) {
            log(LogLevel.ERROR, null, msg, null);
        }

        public static void e(String tag, String msg) {
            log(LogLevel.ERROR, tag, msg, null);
        }

        public static void e(String tag, String msg, Throwable th) {
            log(LogLevel.ERROR, tag, msg, th);
        }

        public static void wtf(Throwable th) {
            log(LogLevel.FATAL, null, null, th);
        }

        public static void wtf(String msg) {
            log(LogLevel.FATAL, null, msg, null);
        }

        public static void wtf(String tag, String msg) {
            log(LogLevel.FATAL, tag, msg, null);
        }

        public static void wtf(String tag, String msg, Throwable th) {
            log(LogLevel.FATAL, tag, msg, th);
        }

        public static void log(int priority, String tag, String msg, Throwable th) {
            LogLevel logLevel = LogLevel.getByPriority(priority);

            if (logLevel != null) {
                log(logLevel, tag, msg, th);
            }
        }

        private static void log(LogLevel logLevel, String tag, String msg, Throwable th) {
            if (remoteLog != null) {
                remoteLog.log(logLevel, tag, msg, th);
            }
        }
    }
}
