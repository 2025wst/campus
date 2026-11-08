package com.zzu.checkin;

import android.app.Application;
import android.content.Context;

/** 只用来提供一个全局 Context，并保证闹钟被安排上。 */
public class App extends Application {

    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CheckinStatus.appendLog("App 启动");
        Notify.ensureChannel(this);
        CheckinAlarm.ensureScheduled(this);
        CheckinJob.schedule(this);
    }

    public static Context get() {
        return instance;
    }
}
