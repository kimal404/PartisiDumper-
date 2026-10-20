package id.dumper.parti

import android.app.Application
import com.topjohnwu.superuser.Shell

class PartiApp : Application() {

    companion object {
        lateinit var instance: PartiApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )
    }
}
