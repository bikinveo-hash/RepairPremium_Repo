/** Run the four independently compiled plugins together, without installing either source plugin. */
fun main() {
    val packages=linkedMapOf("AdiDrakor" to "com.AdiDrakor", "AdiFilmSemi" to "com.michat88",
        "AdiXtream" to "com.adixtream", "Adicinemax21" to "com.Adicinemax21")
    val proxies=packages.map { (module,pkg) ->
        val cls=Class.forName("$pkg.sources.LocalProxy")
        Triple(module,cls,cls.getField("INSTANCE").get(null))
    }
    try {
        val ports=proxies.map { (_,cls,proxy) ->
            check(cls.getMethod("start").invoke(proxy)==true)
            cls.getMethod("getPort").invoke(proxy) as Int
        }
        check(ports.toSet().size==4 && ports.all { it>0 })
        for((module,cls,proxy) in proxies) {
            val pkg=packages.getValue(module)
            val pluginClass=Class.forName("$pkg.${module}Plugin")
            pluginClass.getMethod("beforeUnload").invoke(pluginClass.getConstructor().newInstance())
            check(cls.getMethod("isRunning").invoke(proxy)==false)
        }
        println("PROXY_ISOLATION 1 PASS: four distinct live loopback ports; each plugin unload closes its own listener")
    } finally { proxies.forEach { (_,cls,proxy) -> cls.getMethod("stop").invoke(proxy) } }
}
