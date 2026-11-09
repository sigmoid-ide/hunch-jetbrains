package com.github.sigmoidide.hunch.model

/** A machine the user added with `ferry add`, as `ferry machines --json` lists it. */
data class Machine(val name: String, val host: String, val user: String?, val gpu: String?)

/** What asking an interpreter's Ferry for its machines came to. */
sealed interface MachinesAnswer {
    data class Found(val machines: List<Machine>) : MachinesAnswer

    /** Not installed in this interpreter. */
    object NoFerry : MachinesAnswer

    /** Installed, but older than 0.1.2, which lists machines as JSON. */
    object OldFerry : MachinesAnswer

    data class Failed(val message: String) : MachinesAnswer

    companion object {
        private val NO_MODULE = Regex("""No module named ['"]?ferry\b""")

        /** Reads the outcome of `<python> -m ferry machines --json`. */
        fun of(ok: Boolean, stdout: String, stderr: String): MachinesAnswer {
            if (!ok) {
                if (NO_MODULE.containsMatchIn(stderr)) return NoFerry
                if ("unrecognized arguments: --json" in stderr) return OldFerry
                val last = stderr.trim().lines().lastOrNull()?.removePrefix("ferry: ")
                return Failed(if (last.isNullOrBlank()) "Ferry did not answer." else last)
            }
            val list = (try { Json.parse(stdout) } catch (_: IllegalArgumentException) { null }) as? List<*>
                ?: return Failed("Ferry's machine list could not be read.")
            return Found(list.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val name = m["name"] as? String
                val host = m["host"] as? String
                if (name == null || host == null) null else Machine(name, host, m["user"] as? String, m["gpu"] as? String)
            })
        }
    }
}
