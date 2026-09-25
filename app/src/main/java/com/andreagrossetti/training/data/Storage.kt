package com.andreagrossetti.training.data

import java.io.File

/** Writes via a temp file + rename so a crash never leaves a truncated file. */
fun File.writeAtomically(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    tmp.renameTo(this)
}
