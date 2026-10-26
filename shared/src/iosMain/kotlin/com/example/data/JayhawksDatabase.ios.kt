package com.example.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Opens the database on iPhone: a file in the app's Documents folder, read
 * and written through the SQLite that Room ships (the same engine and version
 * on every iPhone), with queries run off the main thread.
 */
fun createDatabase(): JayhawksDatabase {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
    return Room.databaseBuilder<JayhawksDatabase>(name = "$documents/ku_volleyball.db")
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
