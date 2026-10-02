package com.vrunity.vrapk

import org.json.JSONArray

// What a running script can do to the scene. The game implements it, so the
// interpreter works with plain numbers and never touches the scene's own types.
interface ScriptWorld {
    fun log(text: String)
    fun itemName(index: Int): String
    fun find(name: String): Int
    fun position(index: Int): FloatArray
    fun setPosition(index: Int, v: FloatArray)
    fun rotation(index: Int): FloatArray
    fun setRotation(index: Int, v: FloatArray)
    fun scale(index: Int): FloatArray
    fun setScale(index: Int, v: FloatArray)
    fun color(index: Int): FloatArray
    fun setColor(index: Int, c: FloatArray)
    fun visible(index: Int): Boolean
    fun setVisible(index: Int, on: Boolean)
    fun translate(index: Int, x: Float, y: Float, z: Float)
    fun turn(index: Int, x: Float, y: Float, z: Float)
    fun face(index: Int, x: Float, y: Float, z: Float)
}

class Scripts {
    // ---------- what a script is made of ----------

    private abstract class Node
    private class Lit(val v: Any?) : Node()
    private class Name(val name: String) : Node()
    private class Get(val target: Node, val name: String) : Node()
    private class Call(val target: Node?, val name: String, val args: ArrayList<Node>) : Node()
    private class Make(val type: String, val args: ArrayList<Node>) : Node()
    private class Unary(val op: String, val a: Node) : Node()
    private class Binary(val op: String, val a: Node, val b: Node) : Node()
    private class Cond(val c: Node, val a: Node, val b: Node) : Node()
    private class SetTo(val target: Node, val op: String, val value: Node) : Node()
    private class Post(val target: Node, val add: Boolean) : Node()
    private class Group(val body: ArrayList<Node>) : Node()
    private class Decl(val name: String, val init: Node?) : Node()
    private class If(val c: Node, val a: Node, val b: Node?) : Node()
    private class Loop(val c: Node, val body: Node) : Node()
    private class ForLoop(val init: Node?, val c: Node?, val step: Node?, val body: Node) : Node()
    private class Back(val value: Node?) : Node()
    private class Skip : Node()
    private class Stop(val why: String) : Node()

    private class Method(val name: String, val params: ArrayList<String>, val body: Node)
    private class Program(val fields: ArrayList<Node>, val methods: HashMap<String, Method>)

    private class Scope(val vars: HashMap<String, Any?>, val parent: Scope?) {
        fun get(name: String): Any? {
            var s: Scope? = this
            while (s != null) {
                if (s.vars.containsKey(name)) return s.vars[name]
                s = s.parent
            }
            return null
        }
        fun set(name: String, value: Any?) {
            var s: Scope? = this
            while (s != null) {
                if (s.vars.containsKey(name)) {
                    s.vars[name] = value
                    return
                }
                s = s.parent
            }
            vars[name] = value
        }
        fun declare(name: String, value: Any?) {
            vars[name] = value
        }
    }

    // A scene object a script can reach: its transform, its colour and its place.
    private class Handle(val index: Int)
    // A surface's own look — where its colour lives.
    private class Paint(val index: Int)
    // One of the names that stand for something the game provides.
    private class Builtin(val space: String)

    private class Run(val name: String, val item: Int, val program: Program, val scope: Scope)

    private class BreakSignal : RuntimeException()
    private class ContinueSignal : RuntimeException()
    private class ReturnSignal(val value: Any?) : RuntimeException()
    private class Halt(val why: String) : RuntimeException(why)

    // ---------- state ----------

    private lateinit var world: ScriptWorld
    private val runs = ArrayList<Run>()
    private val random = java.util.Random()
    private var dt = 0f
    private var clock = 0f
    private var frames = 0
    private var steps = 0
    private val reported = HashSet<String>()

    // The scripts the scene carries, each attached to the object it was written for.
    // A script that cannot be read is reported once and left out, so one bad script
    // never stops the scene.
    fun load(list: JSONArray?, world: ScriptWorld) {
        this.world = world
        if (list == null) return
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val code = entry.optString("code", "")
            if (code.trim().isEmpty()) continue
            val name = entry.optString("name", "Object")
            try {
                val program = Parser(lex(code)).parseClass()
                if (program.methods.isEmpty()) {
                    world.log("Script on " + name + " has nothing to run")
                    continue
                }
                runs.add(Run(name, entry.optInt("item", -1), program, Scope(HashMap(), null)))
            } catch (t: Throwable) {
                world.log("Script on " + name + " could not be read: " + t.toString())
            }
        }
    }

    // Awake and Start run once, when the scene begins.
    fun start() {
        val snapshot = ArrayList(runs)
        for (run in snapshot) {
            setup(run)
            invoke(run, "Awake")
            invoke(run, "Start")
        }
    }

    fun step(delta: Float) {
        dt = delta
        clock += delta
        frames++
        val snapshot = ArrayList(runs)
        for (run in snapshot) {
            invoke(run, "Update")
            invoke(run, "FixedUpdate")
            invoke(run, "LateUpdate")
        }
    }

    // A script's own fields, filled in the order they were written.
    private fun setup(run: Run) {
        steps = 0
        try {
            for (field in run.program.fields) eval(field, run.scope, run)
        } catch (h: Halt) {
            fail(run, h.message ?: "the script ran too long")
        } catch (t: Throwable) {
            fail(run, t.toString())
        }
    }

    private fun invoke(run: Run, name: String) {
        val method = run.program.methods[name] ?: return
        steps = 0
        try {
            callMethod(method, ArrayList<Any?>(), run)
        } catch (h: Halt) {
            fail(run, h.message ?: "the script ran too long")
        } catch (t: Throwable) {
            fail(run, t.toString())
        }
    }

    private fun fail(run: Run, why: String) {
        world.log("Script on " + run.name + " stopped: " + why)
        runs.remove(run)
    }

    private fun once(text: String, run: Run) {
        val key = run.name + "|" + text
        if (reported.contains(key)) return
        reported.add(key)
        world.log("Script on " + run.name + ": " + text)
    }

    // ---------- reading the script ----------

    private class Token(val kind: Int, val text: String, val num: Float)

    private fun lex(src: String): ArrayList<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { i++; continue }
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                while (i < n && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) i++
                i += 2
                continue
            }
            if (c == '"' || c == '\'' || ((c == '@' || c == '$') && i + 1 < n && src[i + 1] == '"')) {
                var verbatim = false
                var interp = false
                if (c == '@' || c == '$') {
                    verbatim = c == '@'
                    interp = c == '$'
                    i++
                }
                val quote = if (i < n) src[i] else '"'
                i++
                val sb = StringBuilder()
                while (i < n) {
                    val d = src[i]
                    if (d == quote) {
                        if (verbatim && i + 1 < n && src[i + 1] == quote) {
                            sb.append(quote)
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    if (!verbatim && d == '\\' && i + 1 < n) {
                        val e = src[i + 1]
                        when (e) {
                            'n' -> sb.append(' ')
                            't' -> sb.append(' ')
                            else -> sb.append(e)
                        }
                        i += 2
                        continue
                    }
                    sb.append(d)
                    i++
                }
                out.add(Token(if (interp) KIND_INTERP else KIND_STR, sb.toString(), 0f))
                continue
            }
            if (c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit())) {
                val start = i
                while (i < n && (src[i].isDigit() || src[i] == '.')) i++
                if (i < n && (src[i] == 'e' || src[i] == 'E')) {
                    i++
                    if (i < n && (src[i] == '+' || src[i] == '-')) i++
                    while (i < n && src[i].isDigit()) i++
                }
                val text = src.substring(start, i)
                if (i < n && (src[i] == 'f' || src[i] == 'F' || src[i] == 'd' || src[i] == 'D' || src[i] == 'm' || src[i] == 'M')) i++
                out.add(Token(KIND_NUM, text, text.toFloatOrNull() ?: 0f))
                continue
            }
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                out.add(Token(KIND_WORD, src.substring(start, i), 0f))
                continue
            }
            val two = if (i + 1 < n) src.substring(i, i + 2) else ""
            if (TWO_CHAR.contains(two)) {
                out.add(Token(KIND_PUNCT, two, 0f))
                i += 2
                continue
            }
            out.add(Token(KIND_PUNCT, c.toString(), 0f))
            i++
        }
        out.add(Token(KIND_END, "", 0f))
        return out
    }

    private inner class Parser(val tokens: ArrayList<Token>) {
        var pos = 0

        fun atEnd(): Boolean = peek().kind == KIND_END

        fun peek(): Token = tokens[if (pos < tokens.size) pos else tokens.size - 1]

        fun next(): Token {
            val t = peek()
            if (pos < tokens.size) pos++
            return t
        }

        fun isWord(word: String): Boolean = peek().kind == KIND_WORD && peek().text == word

        fun isPunct(text: String): Boolean = peek().kind == KIND_PUNCT && peek().text == text

        fun accept(text: String): Boolean {
            if (isPunct(text)) {
                pos++
                return true
            }
            return false
        }

        fun skipBalanced(open: String, close: String) {
            if (!isPunct(open)) return
            var depth = 0
            var guard = 0
            while (!atEnd() && guard++ < 20000) {
                val t = next()
                if (t.kind == KIND_PUNCT && t.text == open) depth++
                if (t.kind == KIND_PUNCT && t.text == close) {
                    depth--
                    if (depth <= 0) return
                }
            }
        }

        // Attributes and the class header are not part of what runs.
        fun parseClass(): Program {
            val fields = ArrayList<Node>()
            val methods = HashMap<String, Method>()
            var guard = 0
            while (!atEnd() && guard++ < 20000) {
                if (isWord("class") || isWord("struct") || isWord("interface")) {
                    skipToBodyBrace()
                    break
                }
                pos++
            }
            while (!atEnd()) {
                if (isPunct("[")) {
                    skipBalanced("[", "]")
                    continue
                }
                if (isPunct("}")) {
                    pos++
                    continue
                }
                if (isWord("enum")) {
                    skipEnum()
                    continue
                }
                if (isWord("class") || isWord("struct")) {
                    // A helper type inside the script: read its fields and methods too.
                    skipToBodyBrace()
                    continue
                }
                if (isMethodAhead()) parseMethod(methods)
                else parseField(fields)
            }
            return Program(fields, methods)
        }

        private fun skipToBodyBrace() {
            var guard = 0
            while (!atEnd() && guard++ < 20000) {
                if (isPunct("{")) {
                    pos++
                    return
                }
                if (isPunct(";")) {
                    pos++
                    return
                }
                pos++
            }
        }

        private fun skipEnum() {
            pos++
            var guard = 0
            while (!atEnd() && guard++ < 20000) {
                if (isPunct("{")) {
                    skipBalanced("{", "}")
                    return
                }
                pos++
            }
        }

        // A signature when a bracket comes before any '=' or ';'.
        private fun isMethodAhead(): Boolean {
            var k = pos
            var guard = 0
            while (k < tokens.size && guard++ < 200) {
                val t = tokens[k]
                if (t.kind == KIND_END) return false
                if (t.kind == KIND_PUNCT) {
                    if (t.text == "(") return true
                    if (t.text == "=" || t.text == ";" || t.text == "{" || t.text == "}") return false
                }
                k++
            }
            return false
        }

        private fun parseMethod(methods: HashMap<String, Method>) {
            var name = peek().text
            var guard = 0
            while (!atEnd() && !isPunct("(") && guard++ < 200) {
                val t = next()
                if (t.kind == KIND_WORD) name = t.text
            }
            if (!isPunct("(")) {
                skipToSemicolon()
                return
            }
            accept("(")
            val params = ArrayList<String>()
            var last = ""
            guard = 0
            while (!atEnd() && !isPunct(")") && guard++ < 200) {
                val t = next()
                if (t.kind == KIND_PUNCT && t.text == ",") {
                    if (last.isNotEmpty()) params.add(last)
                    last = ""
                    continue
                }
                if (t.kind == KIND_WORD) last = t.text
            }
            accept(")")
            if (isPunct(";")) {
                pos++
                return
            }
            if (!isPunct("{")) {
                skipToSemicolon()
                return
            }
            val body = block()
            if (name.isNotEmpty()) methods[name] = Method(name, params, body)
        }

        private fun parseField(fields: ArrayList<Node>) {
            var name = ""
            var last = ""
            var guard = 0
            while (!atEnd() && guard++ < 400) {
                if (isPunct(";")) {
                    pos++
                    break
                }
                if (isPunct("=")) {
                    pos++
                    if (name.isEmpty()) name = last
                    val value = expression()
                    if (name.isNotEmpty()) fields.add(Decl(name, value))
                    name = ""
                    if (isPunct(",")) {
                        pos++
                        continue
                    }
                    if (isPunct(";")) {
                        pos++
                        break
                    }
                    continue
                }
                if (isPunct("{")) {
                    skipBalanced("{", "}")
                    continue
                }
                val t = next()
                if (t.kind == KIND_WORD) last = t.text
            }
            if (name.isEmpty()) name = last
            if (name.isNotEmpty() && name != "public" && name != "private" && name != "static") fields.add(Decl(name, null))
        }

        private fun skipToSemicolon() {
            var guard = 0
            while (!atEnd() && guard++ < 400) {
                if (isPunct(";")) {
                    pos++
                    return
                }
                if (isPunct("{")) {
                    skipBalanced("{", "}")
                    return
                }
                pos++
            }
        }

        fun block(): Node {
            accept("{")
            val body = ArrayList<Node>()
            var guard = 0
            while (!atEnd() && !isPunct("}") && guard++ < 20000) body.add(statement())
            accept("}")
            return Group(body)
        }

        fun statement(): Node {
            if (isPunct(";")) {
                pos++
                return Skip()
            }
            if (isPunct("{")) return block()
            if (isWord("if")) {
                pos++
                accept("(")
                val cond = expression()
                accept(")")
                val a = statement()
                var b: Node? = null
                if (isWord("else")) {
                    pos++
                    b = statement()
                }
                return If(cond, a, b)
            }
            if (isWord("while")) {
                pos++
                accept("(")
                val cond = expression()
                accept(")")
                return Loop(cond, statement())
            }
            if (isWord("for")) {
                pos++
                accept("(")
                var init: Node? = null
                if (!isPunct(";")) init = declOrExpr()
                accept(";")
                var cond: Node? = null
                if (!isPunct(";")) cond = expression()
                accept(";")
                var step: Node? = null
                if (!isPunct(")")) step = expression()
                accept(")")
                return ForLoop(init, cond, step, statement())
            }
            if (isWord("return")) {
                pos++
                var value: Node? = null
                if (!isPunct(";")) value = expression()
                accept(";")
                return Back(value)
            }
            if (isWord("break")) {
                pos++
                accept(";")
                return Stop("break") as Node
            }
            if (isWord("continue")) {
                pos++
                accept(";")
                return Stop("continue") as Node
            }
            if (isWord("switch") || isWord("foreach") || isWord("do") || isWord("try") || isWord("using")) {
                val word = peek().text
                skipStatement()
                return Stop(word + " is not read by the app's scripts yet")
            }
            return declOrExpr()
        }

        private fun skipStatement() {
            var guard = 0
            while (!atEnd() && guard++ < 400) {
                if (isPunct(";")) {
                    pos++
                    return
                }
                if (isPunct("{")) {
                    skipBalanced("{", "}")
                    return
                }
                pos++
            }
        }

        // A declaration and an expression look alike until the type is known.
        private fun declOrExpr(): Node {
            var k = pos
            var guard = 0
            while (k < tokens.size && guard++ < 8) {
                val t = tokens[k]
                if (t.kind != KIND_WORD) break
                if (TYPE_WORDS.contains(t.text)) return declaration()
                if (!MODIFIERS.contains(t.text)) break
                k++
            }
            val e = expression()
            accept(";")
            return e
        }

        private fun declaration(): Node {
            var guard = 0
            while (peek().kind == KIND_WORD && MODIFIERS.contains(peek().text) && guard++ < 8) pos++
            if (peek().kind == KIND_WORD) pos++
            if (isPunct("<")) skipBalanced("<", ">")
            while (isPunct("[")) skipBalanced("[", "]")
            var name = ""
            if (peek().kind == KIND_WORD) name = next().text
            if (isPunct("<")) skipBalanced("<", ">")
            var init: Node? = null
            if (accept("=")) init = expression()
            val out = ArrayList<Node>()
            if (name.isNotEmpty()) out.add(Decl(name, init))
            guard = 0
            while (isPunct(",") && guard++ < 32) {
                pos++
                var more = ""
                if (peek().kind == KIND_WORD) more = next().text
                var value: Node? = null
                if (accept("=")) value = expression()
                if (more.isNotEmpty()) out.add(Decl(more, value))
            }
            accept(";")
            return if (out.size == 1) out[0] else Group(out)
        }

        fun expression(): Node = assignment()

        private fun assignment(): Node {
            val left = conditional()
            if (isPunct("=") || isPunct("+=") || isPunct("-=") || isPunct("*=") || isPunct("/=") || isPunct("%=")) {
                val op = next().text
                val right = assignment()
                return SetTo(left, op, right)
            }
            return left
        }

        private fun conditional(): Node {
            val c = binary(0)
            if (isPunct("?")) {
                pos++
                val a = assignment()
                accept(":")
                val b = assignment()
                return Cond(c, a, b)
            }
            return c
        }

        private fun binary(level: Int): Node {
            if (level >= LEVELS.size) return unary()
            var left = binary(level + 1)
            var guard = 0
            while (guard++ < 2000) {
                val t = peek()
                if (t.kind != KIND_PUNCT || !LEVELS[level].contains(t.text)) return left
                pos++
                val right = binary(level + 1)
                left = Binary(t.text, left, right)
            }
            return left
        }

        private fun unary(): Node {
            if (isPunct("!")) {
                pos++
                return Unary("!", unary())
            }
            if (isPunct("-")) {
                pos++
                return Unary("-", unary())
            }
            if (isPunct("+")) {
                pos++
                return unary()
            }
            if (isPunct("++")) {
                pos++
                return SetTo(unary(), "+=", Lit(1f))
            }
            if (isPunct("--")) {
                pos++
                return SetTo(unary(), "-=", Lit(1f))
            }
            return postfix()
        }

        private fun postfix(): Node {
            var node = primary()
            var guard = 0
            while (guard++ < 500) {
                if (isPunct(".")) {
                    pos++
                    if (peek().kind != KIND_WORD) return node
                    val name = next().text
                    if (isPunct("<")) skipBalanced("<", ">")
                    if (isPunct("(")) {
                        pos++
                        val args = arguments()
                        accept(")")
                        node = Call(node, name, args)
                    } else {
                        node = Get(node, name)
                    }
                    continue
                }
                if (isPunct("++")) {
                    pos++
                    node = Post(node, true)
                    continue
                }
                if (isPunct("--")) {
                    pos++
                    node = Post(node, false)
                    continue
                }
                return node
            }
            return node
        }

        private fun arguments(): ArrayList<Node> {
            val out = ArrayList<Node>()
            var guard = 0
            if (isPunct(")")) return out
            while (!atEnd() && guard++ < 200) {
                out.add(expression())
                if (accept(",")) continue
                return out
            }
            return out
        }

        private fun primary(): Node {
            val t = peek()
            if (t.kind == KIND_NUM) {
                pos++
                return Lit(t.num)
            }
            if (t.kind == KIND_STR) {
                pos++
                return Lit(t.text)
            }
            if (t.kind == KIND_INTERP) {
                pos++
                return interpolate(t.text)
            }
            if (t.kind == KIND_WORD) {
                pos++
                val word = t.text
                if (word == "true") return Lit(true)
                if (word == "false") return Lit(false)
                if (word == "null") return Lit(null)
                if (word == "new") {
                    var type = ""
                    if (peek().kind == KIND_WORD) type = next().text
                    if (isPunct("<")) skipBalanced("<", ">")
                    while (isPunct("[")) skipBalanced("[", "]")
                    val args = ArrayList<Node>()
                    if (isPunct("{")) {
                        skipBalanced("{", "}")
                    } else if (isPunct("(")) {
                        pos++
                        val parsed = arguments()
                        accept(")")
                        args.addAll(parsed)
                    }
                    return Make(type, args)
                }
                if (isPunct("(")) {
                    pos++
                    val args = arguments()
                    accept(")")
                    return Call(null, word, args)
                }
                return Name(word)
            }
            if (t.kind == KIND_PUNCT && t.text == "(") {
                pos++
                val e = expression()
                accept(")")
                return e
            }
            pos++
            return Lit(null)
        }
    }

    // A $"..." string: the values in its braces become parts joined together.
    private fun interpolate(text: String): Node {
        val parts = ArrayList<Node>()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '{') {
                var depth = 1
                var j = i + 1
                val expr = StringBuilder()
                while (j < text.length && depth > 0) {
                    val d = text[j]
                    if (d == '{') depth++
                    if (d == '}') {
                        depth--
                        if (depth == 0) break
                    }
                    expr.append(d)
                    j++
                }
                if (sb.isNotEmpty()) {
                    parts.add(Lit(sb.toString()))
                    sb.setLength(0)
                }
                val inner = expr.toString()
                var body = inner
                if (body.contains(":")) body = body.substringBefore(":")
                if (body.trim().isNotEmpty()) parts.add(Parser(lex(body.trim())).expression())
                i = j + 1
                continue
            }
            sb.append(c)
            i++
        }
        if (sb.isNotEmpty()) parts.add(Lit(sb.toString()))
        if (parts.isEmpty()) return Lit("")
        var node: Node = parts[0]
        for (k in 1 until parts.size) node = Binary("+", node, parts[k])
        return node
    }

    // ---------- running the script ----------

    private fun eval(node: Node, scope: Scope, run: Run): Any? {
        steps++
        if (steps > 200000) throw Halt("the script ran too long in one frame")
        if (node is Lit) return node.v
        if (node is Name) return named(node.name, scope, run)
        if (node is Get) return read(eval(node.target, scope, run), node.name, run)
        if (node is Call) {
            val target = if (node.target == null) null else eval(node.target, scope, run)
            val args = ArrayList<Any?>()
            for (a in node.args) args.add(eval(a, scope, run))
            return call(target, node.name, args, run)
        }
        if (node is Make) {
            val args = ArrayList<Any?>()
            for (a in node.args) args.add(eval(a, scope, run))
            return make(node.type, args)
        }
        if (node is Unary) {
            val a = eval(node.a, scope, run)
            if (node.op == "!") return !truth(a)
            return if (a is FloatArray) scaleVec(a, -1f) else number(-number(a))
        }
        if (node is Binary) return binary(node.op, eval(node.a, scope, run), eval(node.b, scope, run))
        if (node is Cond) return if (truth(eval(node.c, scope, run))) eval(node.a, scope, run) else eval(node.b, scope, run)
        if (node is SetTo) return assign(node.target, node.op, eval(node.value, scope, run), scope, run)
        if (node is Post) {
            val old = eval(node.target, scope, run)
            assign(node.target, if (node.add) "+=" else "-=", 1f, scope, run)
            return old
        }
        if (node is Group) {
            val inner = Scope(HashMap(), scope)
            for (child in node.body) eval(child, inner, run)
            return null
        }
        if (node is Decl) {
            val value = if (node.init == null) null else eval(node.init, scope, run)
            scope.declare(node.name, value)
            return value
        }
        if (node is If) {
            if (truth(eval(node.c, scope, run))) return eval(node.a, scope, run)
            if (node.b != null) return eval(node.b, scope, run)
            return null
        }
        if (node is Loop) {
            var guard = 0
            while (truth(eval(node.c, scope, run))) {
                if (guard++ > 200000) throw Halt("a loop in the script never ended")
                try {
                    eval(node.body, scope, run)
                } catch (b: BreakSignal) {
                    break
                } catch (c: ContinueSignal) {
                    // on to the next turn
                }
            }
            return null
        }
        if (node is ForLoop) {
            val loopScope = Scope(HashMap(), scope)
            if (node.init != null) eval(node.init, loopScope, run)
            var guard = 0
            while (node.c == null || truth(eval(node.c, loopScope, run))) {
                if (guard++ > 200000) throw Halt("a loop in the script never ended")
                try {
                    eval(node.body, Scope(HashMap(), loopScope), run)
                } catch (b: BreakSignal) {
                    break
                } catch (c: ContinueSignal) {
                    // the step below still runs
                }
                if (node.step != null) eval(node.step, loopScope, run)
            }
            return null
        }
        if (node is Back) throw ReturnSignal(if (node.value == null) null else eval(node.value, scope, run))
        if (node is Stop) throw Halt(node.why)
        return null
    }

    // The names that stand for something the game provides, and the script's own
    // variables after that.
    private fun named(word: String, scope: Scope, run: Run): Any? {
        if (word == "transform" || word == "gameObject" || word == "this") return if (run.item >= 0) Handle(run.item) else null
        if (word == "renderer" || word == "material") return if (run.item >= 0) Paint(run.item) else null
        if (word == "PI") return Math.PI.toFloat()
        if (NAMES.contains(word)) return Builtin(word)
        return scope.get(word)
    }

    private fun read(target: Any?, name: String, run: Run): Any? {
        if (target is Builtin) {
            if (target.space == "Vector3") return vectorProp(name)
            if (target.space == "Vector2") return vectorProp(name)
            if (target.space == "Color") return colorProp(name)
            if (target.space == "Mathf" || target.space == "Math") return mathProp(name)
            if (target.space == "Quaternion") return if (name == "identity") floatArrayOf(0f, 0f, 0f) else null
            if (target.space == "Random") return if (name == "value") random.nextFloat() else null
            if (target.space == "Time") {
                when (name) {
                    "time", "unscaledTime", "timeSinceLevelLoad" -> return clock
                    "deltaTime", "unscaledDeltaTime", "fixedDeltaTime" -> return dt
                    "timeScale" -> return 1f
                    "frameCount" -> return frames.toFloat()
                    "smoothDeltaTime" -> return dt
                }
                return null
            }
            if (target.space == "Physics" && name == "gravity") return floatArrayOf(0f, -9.81f, 0f)
            if (target.space == "Application" && name == "isPlaying") return true
            once(target.space + "." + name + " is not in the app's scripts yet", run)
            return null
        }
        if (target is Handle) {
            when (name) {
                "position", "localPosition" -> return world.position(target.index)
                "rotation", "localRotation", "eulerAngles" -> return world.rotation(target.index)
                "localScale", "lossyScale" -> return world.scale(target.index)
                "name" -> return world.itemName(target.index)
                "activeSelf", "activeInHierarchy" -> return world.visible(target.index)
                "transform", "gameObject" -> return target
                "renderer", "material" -> return Paint(target.index)
            }
            return null
        }
        if (target is Paint) {
            if (name == "color") return world.color(target.index)
            if (name == "material") return target
            return null
        }
        if (target is FloatArray) {
            val at = component(name)
            if (at >= 0) return target[at]
            if (name == "magnitude") return length(target)
            if (name == "sqrMagnitude") return length(target) * length(target)
            if (name == "normalized") {
                val m = length(target)
                if (m < 0.000001f) return floatArrayOf(0f, 0f, 0f)
                return floatArrayOf(target[0] / m, target[1] / m, target[2] / m)
            }
            return null
        }
        if (target is String && name == "Length") return target.length.toFloat()
        return null
    }

    private fun call(target: Any?, name: String, args: ArrayList<Any?>, run: Run): Any? {
        if (target is Builtin) return builtinCall(target.space, name, args, run)
        if (target is Handle) {
            when (name) {
                "Rotate" -> {
                    world.turn(target.index, number(args.getOrNull(0)), number(args.getOrNull(1)), number(args.getOrNull(2)))
                    return null
                }
                "Translate" -> {
                    world.translate(target.index, number(args.getOrNull(0)), number(args.getOrNull(1)), number(args.getOrNull(2)))
                    return null
                }
                "LookAt" -> {
                    val a = args.getOrNull(0)
                    if (a is Handle) {
                        val p = world.position(a.index)
                        world.face(target.index, p[0], p[1], p[2])
                    } else {
                        world.face(target.index, number(args.getOrNull(0)), number(args.getOrNull(1)), number(args.getOrNull(2)))
                    }
                    return null
                }
                "SetActive" -> {
                    world.setVisible(target.index, truth(args.getOrNull(0)))
                    return null
                }
                "GetComponent" -> return target
                "ToString" -> return world.itemName(target.index)
            }
            return null
        }
        if (target is Paint) return null
        if (target is FloatArray) {
            when (name) {
                "Normalize" -> {
                    val m = length(target)
                    if (m > 0.000001f) {
                        target[0] /= m
                        target[1] /= m
                        target[2] /= m
                    }
                    return null
                }
                "ToString" -> return vectorText(target)
            }
            return null
        }
        if (target is String) {
            when (name) {
                "ToString" -> return target
                "ToUpper", "ToUpperInvariant" -> return target.uppercase()
                "ToLower", "ToLowerInvariant" -> return target.lowercase()
                "Contains" -> return target.contains(text(args.getOrNull(0)))
                "Trim" -> return target.trim()
                "Substring" -> {
                    val from = number(args.getOrNull(0)).toInt()
                    if (from < 0 || from > target.length) return target
                    if (args.size > 1) {
                        val len = number(args.getOrNull(1)).toInt()
                        val to = Math.min(target.length, from + Math.max(0, len))
                        return target.substring(from, to)
                    }
                    return target.substring(from)
                }
                "StartsWith" -> return target.startsWith(text(args.getOrNull(0)))
                "Replace" -> return target.replace(text(args.getOrNull(0)), text(args.getOrNull(1)))
                "Length" -> return target.length.toFloat()
            }
            return null
        }
        // A call with nothing in front of it: the script's own method, or one of the
        // few names the game answers directly.
        if (name == "find" || name == "Find") {
            val at = world.find(text(args.getOrNull(0)))
            return if (at >= 0) Handle(at) else null
        }
        if (name == "print" || name == "Debug") {
            val sb = StringBuilder()
            if (args.size == 1) {
                sb.append(text(args[0]))
            } else if (args.size > 1) {
                sb.append(text(args[0]))
                for (k in 1 until args.size) sb.append(" ").append(text(args[k]))
            }
            world.log(sb.toString())
            return null
        }
        val method = run.program.methods[name]
        if (method != null) return callMethod(method, args, run)
        once(name + "() is not in the app's scripts yet", run)
        return null
    }

    private fun callMethod(method: Method, args: ArrayList<Any?>, run: Run): Any? {
        val scope = Scope(HashMap(), run.scope)
        for (i in method.params.indices) scope.declare(method.params[i], if (i < args.size) args[i] else null)
        try {
            eval(method.body, scope, run)
        } catch (r: ReturnSignal) {
            return r.value
        }
        return null
    }

    private fun builtinCall(space: String, name: String, args: ArrayList<Any?>, run: Run): Any? {
        val a0 = args.getOrNull(0)
        val a1 = args.getOrNull(1)
        val a2 = args.getOrNull(2)
        if (space == "Debug") {
            val sb = StringBuilder()
            for (k in args.indices) {
                if (k > 0) sb.append(" ")
                sb.append(text(args[k]))
            }
            world.log(sb.toString())
            return null
        }
        if (space == "Vector3" || space == "Vector2") {
            when (name) {
                "Distance" -> return distance(vec(a0), vec(a1))
                "Lerp", "LerpUnclamped" -> {
                    val a = vec(a0)
                    val b = vec(a1)
                    val t = if (name == "Lerp") clamp01(number(a2)) else number(a2)
                    return floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
                }
                "MoveTowards" -> return moveTowards(vec(a0), vec(a1), number(a2))
                "Normalize" -> {
                    val v = vec(a0)
                    val m = length(v)
                    if (m < 0.000001f) return floatArrayOf(0f, 0f, 0f)
                    return floatArrayOf(v[0] / m, v[1] / m, v[2] / m)
                }
                "Scale", "Multiply" -> return mulVec(vec(a0), vec(a1))
                "Cross" -> {
                    val a = vec(a0)
                    val b = vec(a1)
                    return floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
                }
                "Dot" -> {
                    val a = vec(a0)
                    val b = vec(a1)
                    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
                }
                "Magnitude" -> return length(vec(a0))
                "SqrMagnitude" -> {
                    val m = length(vec(a0))
                    return m * m
                }
                "Angle" -> {
                    val a = vec(a0)
                    val b = vec(a1)
                    val d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
                    val m = length(a) * length(b)
                    if (m < 0.000001f) return 0f
                    val c = clamp(d / m, -1f, 1f)
                    return (Math.acos(c.toDouble()) * 180.0 / Math.PI).toFloat()
                }
                "ClampMagnitude" -> {
                    val v = vec(a0)
                    val max = number(a1)
                    val m = length(v)
                    if (m <= max || m < 0.000001f) return v
                    return floatArrayOf(v[0] / m * max, v[1] / m * max, v[2] / m * max)
                }
                "Min" -> return minVec(vec(a0), vec(a1))
                "Max" -> return maxVec(vec(a0), vec(a1))
                "Project", "Reflect" -> return vec(a0)
            }
            once("Vector3." + name + "() is not in the app's scripts yet", run)
            return null
        }
        if (space == "Mathf" || space == "Math") {
            val x = number(a0)
            val y = number(a1)
            when (name) {
                "Abs" -> return Math.abs(x)
                "Sin" -> return Math.sin(x.toDouble()).toFloat()
                "Cos" -> return Math.cos(x.toDouble()).toFloat()
                "Tan" -> return Math.tan(x.toDouble()).toFloat()
                "Asin" -> return Math.asin(clamp(x, -1f, 1f).toDouble()).toFloat()
                "Acos" -> return Math.acos(clamp(x, -1f, 1f).toDouble()).toFloat()
                "Atan" -> return Math.atan(x.toDouble()).toFloat()
                "Atan2" -> return Math.atan2(y.toDouble(), number(a1).toDouble()).toFloat()
                "Sqrt" -> return if (x < 0f) 0f else Math.sqrt(x.toDouble()).toFloat()
                "Pow" -> return Math.pow(x.toDouble(), y.toDouble()).toFloat()
                "Exp" -> return Math.exp(x.toDouble()).toFloat()
                "Log" -> return Math.log(x.toDouble()).toFloat()
                "Min" -> return Math.min(x, number(a1))
                "Max" -> return Math.max(x, number(a1))
                "Clamp" -> return clamp(x, number(a1), number(a2))
                "Clamp01" -> return clamp01(x)
                "Lerp", "LerpUnclamped" -> return x + (number(a1) - x) * number(a2)
                "InverseLerp" -> {
                    val b = number(a1)
                    if (Math.abs(b - x) < 0.000001f) return 0f
                    return clamp01((number(a2) - x) / (b - x))
                }
                "MoveTowards" -> return moveTowards(x, number(a1), number(a2))
                "Floor" -> return Math.floor(x.toDouble()).toFloat()
                "Ceil" -> return Math.ceil(x.toDouble()).toFloat()
                "Round" -> return Math.round(x)
                "Sign" -> return if (x < 0f) -1f else if (x > 0f) 1f else 0f
                "Repeat" -> {
                    val t = number(a1)
                    if (t <= 0f) return 0f
                    return x - t * Math.floor((x / t).toDouble()).toFloat()
                }
                "PingPong" -> {
                    val t = number(a1)
                    if (t <= 0f) return 0f
                    val raw = x - t * Math.floor((x / t).toDouble()).toFloat()
                    return if (raw <= t * 0.5f) raw else t - raw
                }
                "Deg2Rad" -> return (x.toDouble() * Math.PI / 180.0).toFloat()
                "Rad2Deg" -> return (x.toDouble() * 180.0 / Math.PI).toFloat()
                "Approximately" -> return Math.abs(x - number(a1)) < 0.00001f
            }
            once("Mathf." + name + "() is not in the app's scripts yet", run)
            return null
        }
        if (space == "Random") {
            when (name) {
                "Range" -> {
                    val lo = number(a0)
                    val hi = number(a1)
                    if (lo == Math.floor(lo.toDouble()).toFloat() && hi == Math.floor(hi.toDouble()).toFloat()) {
                        val span = hi - lo
                        if (span <= 0f) return lo
                        return lo + Math.floor((random.nextFloat() * span).toDouble()).toFloat()
                    }
                    return lo + random.nextFloat() * (hi - lo)
                }
                "insideUnitSphere", "onUnitSphere" -> {
                    val theta = random.nextFloat() * 6.2831855f
                    val z = random.nextFloat() * 2f - 1f
                    val r = Math.sqrt(Math.max(0.0, (1.0 - z.toDouble() * z.toDouble())).toDouble()).toFloat()
                    return floatArrayOf(r * Math.cos(theta.toDouble()).toFloat(), r * Math.sin(theta.toDouble()).toFloat(), z)
                }
                "insideUnitCircle" -> {
                    val theta = random.nextFloat() * 6.2831855f
                    return floatArrayOf(Math.cos(theta.toDouble()).toFloat(), Math.sin(theta.toDouble()).toFloat(), 0f)
                }
                "value" -> return random.nextFloat()
                "ColorHSV" -> return floatArrayOf(random.nextFloat(), random.nextFloat(), random.nextFloat(), 1f)
            }
            once("Random." + name + "() is not in the app's scripts yet", run)
            return null
        }
        if (space == "Quaternion") {
            when (name) {
                "Euler" -> return floatArrayOf(number(a0), number(a1), number(a2))
                "Angle" -> return 0f
                "LookRotation" -> return floatArrayOf(0f, 0f, 0f)
                "identity" -> return floatArrayOf(0f, 0f, 0f)
            }
            return null
        }
        if (space == "GameObject" || space == "Object") {
            if (name == "Find") {
                val at = world.find(text(a0))
                return if (at >= 0) Handle(at) else null
            }
            once("GameObject." + name + "() is not in the app's scripts yet", run)
            return null
        }
        if (space == "Physics") {
            once("Physics." + name + "() is not in the app's scripts yet", run)
            return null
        }
        once(space + "." + name + "() is not in the app's scripts yet", run)
        return null
    }

    private fun make(type: String, args: ArrayList<Any?>): Any? {
        if (type == "Vector3" || type == "Vector2" || type == "Vector2Int" || type == "Vector3Int") {
            return floatArrayOf(number(args.getOrNull(0)), number(args.getOrNull(1)), number(args.getOrNull(2)))
        }
        if (type == "Quaternion") return floatArrayOf(number(args.getOrNull(0)), number(args.getOrNull(1)), number(args.getOrNull(2)))
        if (type == "Color") {
            return floatArrayOf(
                clamp01(number(args.getOrNull(0))),
                clamp01(number(args.getOrNull(1))),
                clamp01(number(args.getOrNull(2))),
                if (args.size > 3) clamp01(number(args.getOrNull(3))) else 1f)
        }
        if (type == "Color32") {
            return floatArrayOf(
                clamp01(number(args.getOrNull(0)) / 255f),
                clamp01(number(args.getOrNull(1)) / 255f),
                clamp01(number(args.getOrNull(2)) / 255f),
                if (args.size > 3) clamp01(number(args.getOrNull(3)) / 255f) else 1f)
        }
        return null
    }

    private fun assign(target: Node, op: String, value: Any?, scope: Scope, run: Run): Any? {
        var next = value
        if (op != "=") {
            val current = eval(target, scope, run)
            val delta = if (op == "+=" || op == "-=") {
                if (op == "-=") negate(value) else value
            } else null
            if (delta != null) next = add(current, delta) else next = mulDiv(current, value, op)
        }
        if (target is Name) {
            scope.set(target.name, next)
            return next
        }
        if (target is Get) {
            // transform.position.x — the piece is replaced and the whole written back,
            // so a component of a position can be set on its own.
            val at = component(target.name)
            if (at >= 0 && target.target is Get) {
                val holder = eval(target.target as Get, scope, run)
                if (holder is FloatArray) {
                    val copy = floatArrayOf(if (holder.size > 0) holder[0] else 0f,
                        if (holder.size > 1) holder[1] else 0f,
                        if (holder.size > 2) holder[2] else 0f,
                        if (holder.size > 3) holder[3] else 1f)
                    copy[at] = number(next)
                    return assign(target.target as Get, "=", if (at == 3) copy else floatArrayOf(copy[0], copy[1], copy[2]), scope, run)
                }
            }
            return writeMember(target, next, run)
        }
        return next
    }

    private fun writeMember(target: Get, value: Any?, run: Run): Any? {
        val holder = target.target
        val owner: Any? = if (holder is Name) named(holder.name, Scope(HashMap(), null), run) else null
        val item = when {
            holder is Name && (holder.name == "transform" || holder.name == "gameObject") -> run.item
            holder is Name && (holder.name == "renderer" || holder.name == "material") -> run.item
            else -> -1
        }
        if (item < 0) return value
        when (target.name) {
            "position", "localPosition" -> world.setPosition(item, vec(value))
            "rotation", "localRotation", "eulerAngles" -> world.setRotation(item, vec(value))
            "localScale", "scale" -> world.setScale(item, vec3(value, 1f))
            "color" -> world.setColor(item, colorValue(value))
            "R", "G", "B", "A" -> return value
            else -> {
                // A chain such as renderer.material.color written from the outside.
                val readBack = read(owner, target.name, run)
                if (readBack is FloatArray) return value
            }
        }
        return value
    }

    // ---------- the values a script works with ----------

    private fun number(v: Any?): Float = when (v) {
        is Float -> v
        is Int -> v.toFloat()
        is Double -> v.toFloat()
        is Boolean -> if (v) 1f else 0f
        is String -> v.toFloatOrNull() ?: 0f
        is FloatArray -> v[0]
        else -> 0f
    }

    private fun truth(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Float -> v != 0f
        is String -> v.isNotEmpty()
        else -> true
    }

    private fun vec(v: Any?): FloatArray = when (v) {
        is FloatArray -> floatArrayOf(
            if (v.size > 0) v[0] else 0f,
            if (v.size > 1) v[1] else 0f,
            if (v.size > 2) v[2] else 0f)
        is Handle -> world.position(v.index)
        else -> floatArrayOf(0f, 0f, 0f)
    }

    private fun vec3(v: Any?, z: Float): FloatArray = when (v) {
        is FloatArray -> floatArrayOf(
            if (v.size > 0) v[0] else 0f,
            if (v.size > 1) v[1] else 0f,
            if (v.size > 2) v[2] else z)
        else -> floatArrayOf(number(v), number(v), number(v))
    }

    private fun colorValue(v: Any?): FloatArray = when (v) {
        is FloatArray -> floatArrayOf(
            clamp01(if (v.size > 0) v[0] else 1f),
            clamp01(if (v.size > 1) v[1] else 1f),
            clamp01(if (v.size > 2) v[2] else 1f),
            if (v.size > 3) clamp01(v[3]) else 1f)
        else -> floatArrayOf(1f, 1f, 1f, 1f)
    }

    private fun text(v: Any?): String = when (v) {
        null -> "null"
        is Float -> trim(v)
        is Boolean -> if (v) "True" else "False"
        is FloatArray -> vectorText(v)
        is Handle -> world.itemName(v.index)
        else -> v.toString()
    }

    private fun trim(v: Float): String {
        val r = Math.round(v)
        if (Math.abs(v - r) < 0.0001f) return r.toString()
        return v.toString()
    }

    private fun vectorText(v: FloatArray): String {
        val sb = StringBuilder("(")
        for (i in 0 until Math.min(3, v.size)) {
            if (i > 0) sb.append(", ")
            sb.append(trim(v[i]))
        }
        return sb.append(")").toString()
    }

    private fun component(name: String): Int = when (name) {
        "x", "r" -> 0
        "y", "g" -> 1
        "z", "b" -> 2
        "w", "a" -> 3
        else -> -1
    }

    private fun length(v: FloatArray): Float {
        val x = if (v.size > 0) v[0] else 0f
        val y = if (v.size > 1) v[1] else 0f
        val z = if (v.size > 2) v[2] else 0f
        return Math.hypot(Math.hypot(x.toDouble(), y.toDouble()), z.toDouble()).toFloat()
    }

    private fun distance(a: FloatArray, b: FloatArray): Float = length(floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2]))

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    private fun clamp01(v: Float): Float = clamp(v, 0f, 1f)

    private fun moveTowards(from: Float, to: Float, step: Float): Float {
        val d = to - from
        if (Math.abs(d) <= step) return to
        return from + if (d > 0f) step else -step
    }

    private fun moveTowards(from: FloatArray, to: FloatArray, step: Float): FloatArray {
        val d = floatArrayOf(to[0] - from[0], to[1] - from[1], to[2] - from[2])
        val m = length(d)
        if (m <= step || m < 0.000001f) return floatArrayOf(to[0], to[1], to[2])
        return floatArrayOf(from[0] + d[0] / m * step, from[1] + d[1] / m * step, from[2] + d[2] / m * step)
    }

    private fun add(a: Any?, b: Any?): Any? {
        if (a is String || b is String) return text(a) + text(b)
        if (a is FloatArray || b is FloatArray) {
            val x = vec(a)
            val y = vec(b)
            return floatArrayOf(x[0] + y[0], x[1] + y[1], x[2] + y[2])
        }
        return number(a) + number(b)
    }

    private fun negate(v: Any?): Any? = if (v is FloatArray) scaleVec(v, -1f) else number(-number(v))

    private fun mulDiv(a: Any?, b: Any?, op: String): Any? {
        if (op == "*" && (a is FloatArray || b is FloatArray)) {
            return if (a is FloatArray && b is FloatArray) mulVec(a, b) else scaleVec(vec(a), number(if (a is FloatArray) b else a))
        }
        if (op == "/") {
            val d = number(b)
            if (a is FloatArray) return if (d == 0f) a else floatArrayOf(a[0] / d, a[1] / d, a[2] / d)
            if (d == 0f) return 0f
            return number(a) / d
        }
        return number(a) * number(b)
    }

    private fun scaleVec(v: FloatArray, s: Float): FloatArray = floatArrayOf(v[0] * s, v[1] * s, v[2] * s)

    private fun mulVec(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(a[0] * b[0], a[1] * b[1], a[2] * b[2])

    private fun minVec(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]))

    private fun maxVec(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]))

    private fun binary(op: String, a: Any?, b: Any?): Any? {
        when (op) {
            "&&" -> return truth(a) && truth(b)
            "||" -> return truth(a) || truth(b)
            "==" -> return same(a, b)
            "!=" -> return !same(a, b)
            "<" -> return number(a) < number(b)
            ">" -> return number(a) > number(b)
            "<=" -> return number(a) <= number(b)
            ">=" -> return number(a) >= number(b)
            "+" -> return add(a, b)
            "-" -> {
                if (a is FloatArray || b is FloatArray) {
                    val x = vec(a)
                    val y = vec(b)
                    return floatArrayOf(x[0] - y[0], x[1] - y[1], x[2] - y[2])
                }
                return number(a) - number(b)
            }
            "*", "/", "%" -> {
                if (op == "%") {
                    val d = number(b)
                    if (d == 0f) return 0f
                    return number(a) - d * Math.floor((number(a) / d).toDouble()).toFloat()
                }
                return mulDiv(a, b, op)
            }
        }
        return null
    }

    private fun same(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        if (a is String || b is String) return a is String && b is String && a == b
        if (a is FloatArray && b is FloatArray) return a[0] == b[0] && a[1] == b[1] && a[2] == b[2]
        return number(a) == number(b)
    }

    private fun vectorProp(name: String): Any? = when (name) {
        "zero" -> floatArrayOf(0f, 0f, 0f)
        "one" -> floatArrayOf(1f, 1f, 1f)
        "up" -> floatArrayOf(0f, 1f, 0f)
        "down" -> floatArrayOf(0f, -1f, 0f)
        "left" -> floatArrayOf(-1f, 0f, 0f)
        "right" -> floatArrayOf(1f, 0f, 0f)
        "forward" -> floatArrayOf(0f, 0f, 1f)
        "back" -> floatArrayOf(0f, 0f, -1f)
        else -> null
    }

    private fun colorProp(name: String): Any? = when (name) {
        "white" -> floatArrayOf(1f, 1f, 1f, 1f)
        "black" -> floatArrayOf(0f, 0f, 0f, 1f)
        "red" -> floatArrayOf(1f, 0f, 0f, 1f)
        "green" -> floatArrayOf(0f, 1f, 0f, 1f)
        "blue" -> floatArrayOf(0f, 0f, 1f, 1f)
        "yellow" -> floatArrayOf(1f, 0.92f, 0.016f, 1f)
        "cyan" -> floatArrayOf(0f, 1f, 1f, 1f)
        "magenta" -> floatArrayOf(1f, 0f, 1f, 1f)
        "gray", "grey" -> floatArrayOf(0.5f, 0.5f, 0.5f, 1f)
        "clear" -> floatArrayOf(0f, 0f, 0f, 0f)
        else -> null
    }

    private fun mathProp(name: String): Any? = when (name) {
        "PI" -> Math.PI.toFloat()
        "Infinity" -> Float.MAX_VALUE
        "NegativeInfinity" -> -Float.MAX_VALUE
        "Deg2Rad" -> (Math.PI / 180.0).toFloat()
        "Rad2Deg" -> (180.0 / Math.PI).toFloat()
        "Epsilon" -> 0.000001f
        else -> null
    }

    companion object {
        private const val KIND_WORD = 0
        private const val KIND_NUM = 1
        private const val KIND_STR = 2
        private const val KIND_PUNCT = 3
        private const val KIND_END = 4
        private const val KIND_INTERP = 5

        private val TWO_CHAR = arrayOf("==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "=>")

        private val LEVELS = arrayOf(
            arrayOf("||"),
            arrayOf("&&"),
            arrayOf("==", "!="),
            arrayOf("<", ">", "<=", ">="),
            arrayOf("+", "-"),
            arrayOf("*", "/", "%"))

        private val TYPE_WORDS = HashSet(listOf(
            "float", "int", "double", "bool", "string", "char", "byte", "short", "long", "uint", "ulong", "var", "void",
            "Vector2", "Vector3", "Vector4", "Vector2Int", "Vector3Int", "Color", "Color32", "Quaternion", "GameObject",
            "Transform", "Component", "Object", "Material", "Renderer", "MeshRenderer", "MeshFilter", "Collider",
            "Rigidbody", "Light", "AudioSource", "List", "Dictionary", "HashSet", "Queue", "Stack", "Ray", "RaycastHit",
            "Bounds", "Rect", "Array", "IEnumerator", "IEnumerable", "KeyCode", "LayerMask"))

        private val MODIFIERS = HashSet(listOf(
            "public", "private", "protected", "internal", "static", "readonly", "const", "sealed", "override",
            "virtual", "abstract", "partial", "async", "extern", "volatile", "new", "unsafe", "in", "out", "ref"))

        // The names that stand for something the game provides rather than a variable.
        private val NAMES = HashSet(listOf(
            "Time", "Vector2", "Vector3", "Vector4", "Vector2Int", "Vector3Int", "Mathf", "Math", "Random", "Debug",
            "Quaternion", "Color", "Color32", "GameObject", "Object", "Transform", "Input", "Physics", "VR", "Audio",
            "AudioSource", "Renderer", "Material", "Light", "Camera", "Screen", "Application", "PlayerPrefs",
            "SceneManager", "Resources", "JsonUtility", "SystemInfo", "QualitySettings", "Animation", "Animator",
            "KeyCode", "LayerMask", "NavMesh", "NavMeshAgent", "Inventory", "Particles", "ParticleSystem", "ObjectPool",
            "GameMode", "Triggers", "WaveSpawner", "DayNight", "EventBus", "Health", "Procedural", "Rigidbody",
            "Collider", "RaycastHit", "Bounds", "Rect", "Space", "World", "TextMesh", "MonoBehaviour"))
    }
}
