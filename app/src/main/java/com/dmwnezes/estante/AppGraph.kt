package com.dmwnezes.estante

import android.content.Context
import coil.ImageLoader
import com.dmwnezes.estante.data.Covers
import com.dmwnezes.estante.data.Library
import com.dmwnezes.estante.data.Tmdb
import android.content.SharedPreferences
import com.dmwnezes.estante.drive.DriveAuth
import com.dmwnezes.estante.drive.DriveClient
import com.dmwnezes.estante.drive.DriveTokenInterceptor
import okhttp3.OkHttpClient
import java.io.File
import java.time.Duration

/** Peças compartilhadas do app, criadas uma vez na abertura. */
object AppGraph {
    lateinit var http: OkHttpClient; private set
    /** Cliente com o token do Google: Drive, streaming e miniaturas. */
    lateinit var driveHttp: OkHttpClient; private set
    lateinit var auth: DriveAuth; private set
    lateinit var drive: DriveClient; private set
    lateinit var library: Library; private set
    lateinit var covers: Covers; private set
    lateinit var images: ImageLoader; private set
    lateinit var tmdb: Tmdb; private set
    lateinit var prefs: SharedPreferences; private set

    private var ready = false

    fun init(context: Context) {
        if (ready) return
        val app = context.applicationContext
        http = OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(30))
            .build()
        auth = DriveAuth(app)
        driveHttp = http.newBuilder().addInterceptor(DriveTokenInterceptor(auth)).build()
        drive = DriveClient(driveHttp)
        library = Library(File(app.filesDir, "estante.json"))
        covers = Covers(app, driveHttp)
        images = ImageLoader.Builder(app).okHttpClient(driveHttp).crossfade(true).build()
        tmdb = Tmdb(app, http)
        prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        ready = true
    }
}
