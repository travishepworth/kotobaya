package kotobaya.auth

import kotlin.system.exitProcess

/**
 * First-run password setup: prints the PHC string to put into the `AUTH_PASSWORD_HASH` secret.
 *
 *     java -cp server-all.jar kotobaya.auth.HashPasswordKt          # prompts (no echo) or reads one line of stdin
 *     printf '%s' "$PW" | java -cp server-all.jar kotobaya.auth.HashPasswordKt
 */
fun main() {
    val console = System.console()
    val password = if (console != null) {
        val a = console.readPassword("Password: ")?.let { String(it) }
        val b = console.readPassword("Repeat: ")?.let { String(it) }
        if (a != b) {
            System.err.println("passwords differ")
            exitProcess(1)
        }
        a
    } else {
        readlnOrNull()
    }
    if (password.isNullOrEmpty() || password.length > 1024) {
        System.err.println("password must be 1 to 1024 characters")
        exitProcess(1)
    }
    println(PasswordHasher.hash(password))
}
