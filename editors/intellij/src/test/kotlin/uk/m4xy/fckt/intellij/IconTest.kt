package uk.m4xy.fckt.intellij

import kotlin.test.Test
import kotlin.test.assertSame

class IconTest {
    @Test
    fun `a file shows what it declares of its own name`() {
        assertSame(FcktIcons.TYPE, declaredIcon("import fckt.io.Console\n\ntype Task(title: String)\n", "Task"))
        assertSame(FcktIcons.TYPE, declaredIcon("internal opaque type Board<X>(size: Int)\n", "Board"))
        assertSame(FcktIcons.SPEC, declaredIcon("spec Plugin {\n  name(): String\n}\n", "Plugin"))
        assertSame(FcktIcons.MODULE, declaredIcon("// Locks\ninternal module Locks {\n}\n", "Locks"))
        assertSame(FcktIcons.MODULE, declaredIcon("module Fckt(Configuration: FcktConfiguration) {\n}\n", "Fckt"))
        assertSame(FcktIcons.EFFECT, declaredIcon("effect Log {\n  log(line: String): Unit\n}\n", "Log"))
        assertSame(FcktIcons.ANNOTATION, declaredIcon("annotation type Serializable\n", "Serializable"))
    }

    @Test
    fun `any other file shows fckt's`() {
        assertSame(FcktIcons.FILE, declaredIcon("main() = println(\"hi\")\n", "main"))
        assertSame(FcktIcons.FILE, declaredIcon("module Builds {\n  type Task(name: String)\n}\n", "Task"))
        assertSame(FcktIcons.FILE, declaredIcon("/* type Task */\nmain() = Unit\n", "Task"))
        assertSame(FcktIcons.FILE, declaredIcon("main() = println(\"type Task\")\n", "Task"))
        assertSame(FcktIcons.FILE, declaredIcon("type Other\nTask() = 1\n", "Task"))
    }
}
