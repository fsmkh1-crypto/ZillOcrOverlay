package kr.co.zillocr.overlay.data

import android.content.Context

object AppContextHolder {
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            synchronized(this) {
                if (appContext == null) appContext = context.applicationContext
            }
        }
    }

    fun require(): Context = checkNotNull(appContext) {
        "Application context has not been initialized"
    }
}
