package android.util
object Log {
    @JvmStatic fun d(tag: String, message: String) = 0
    @JvmStatic fun e(tag: String, message: String) = 0
    @JvmStatic fun w(tag: String, message: String) = 0
}
object Base64 {
    const val DEFAULT = 0
    const val NO_WRAP = 2
    @JvmStatic fun decode(value: String, flags: Int): ByteArray = java.util.Base64.getMimeDecoder().decode(value)
    @JvmStatic fun encodeToString(value: ByteArray, flags: Int): String = java.util.Base64.getEncoder().encodeToString(value)
}
