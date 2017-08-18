package org.wordpress.android.editor.example;

import android.app.Application;
import android.content.Context;

/**
 * @author 诸葛不亮
 * @version 1.0
 * @description
 */

public class WordPress extends Application {
    private static Context sContext;

    public static Context getContext() {
        return sContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sContext = this;
    }


}
