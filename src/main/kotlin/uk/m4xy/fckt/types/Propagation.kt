package uk.m4xy.fckt.types

import uk.m4xy.fckt.syntax.Annotation
import uk.m4xy.fckt.syntax.Decl

/**
 * The annotations [decl] has, once checked: its own, and those of [enclosing], the modules it's
 * in, outermost first, whose annotation types are `@Propagates`. Of a `@Repeatable` one, each
 * module's and its own, in that order; of another, the nearest's.
 */
fun effectiveAnnotations(decl: Decl, enclosing: List<Decl.Module>): List<Annotation> {
    val levels = enclosing.map { m -> m.annotations.filter { a -> a.decl?.let { marked(it, PROPAGATES) } == true } } + listOf(decl.annotations)
    val result = mutableListOf<Annotation>()
    for (level in levels) {
        for (a in level) {
            val type = a.decl
            if (type != null && !marked(type, REPEATABLE)) result.removeAll { it.decl === type }
            result += a
        }
    }
    return result
}

/** Whether [d] is annotated with the annotation type [name], once checked. */
private fun marked(d: Decl, name: String): Boolean = d.annotations.any { it.decl?.name == name }
