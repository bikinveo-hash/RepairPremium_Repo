package android.media

/** JVM-only Android codec API substitute. Production compiles against the real API. */
object MediaCodecList {
    @JvmStatic fun getCodecCount(): Int = 0
    @JvmStatic fun getCodecInfoAt(index: Int): MediaCodecInfo = error("No Android codecs in JVM fixture")
}
