import java.io.File
import java.security.MessageDigest
import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens
import org.json.JSONArray
import org.json.JSONObject

/** Compare executable token streams, preserving string contents and token boundaries.
 * Imports can be reordered or unused imports removed. Optional separators/trivia are ignored.
 * Compilation and behavioral fixtures additionally verify Kotlin newline/semicolon semantics.
 */
fun main(args: Array<String>) {
    val output = JSONArray()
    for (path in args) {
        val raw = File(path).readText()
        val imports = Regex("(?m)^import[^\\n]+").findAll(raw).map { it.value.trim() }.toList()
        val code = Regex("(?m)^import[^\\n]+").replace(raw, "")
        val lexer = KotlinLexer()
        lexer.start(code)
        val tokens = mutableListOf<Pair<String, String>>()
        while (lexer.tokenType != null) {
            val type = lexer.tokenType!!
            if (type.toString() != "WHITE_SPACE" && !KtTokens.COMMENTS.contains(type) && type != KtTokens.SEMICOLON) {
                tokens += type.toString() to code.substring(lexer.tokenStart, lexer.tokenEnd)
            }
            lexer.advance()
        }
        val stable = tokens.filterIndexed { i, token ->
            token.first != "COMMA" || tokens.getOrNull(i + 1)?.first !in setOf("RPAR", "RBRACKET", "RBRACE", "GT", "ARROW")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        for ((kind, value) in stable) {
            digest.update("${kind.length}:$kind${value.length}:$value".toByteArray(Charsets.UTF_8))
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        output.put(JSONObject().put("path", path).put("semanticSha256", hash).put("imports", JSONArray(imports)))
    }
    println(output)
}
