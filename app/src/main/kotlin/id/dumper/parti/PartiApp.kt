package id.dumper.parti

import android.app.Application
import com.topjohnwu.superuser.Shell

class PartiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(15)
        )
    }
}
