package com.maik.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * A single stray `%` in a translated string throws when Android formats it, which
 * crashes whatever screen shows that text. Lint only catches some of these.
 */
class StringResourcesTest {

    private val strings: List<Pair<String, String>> by lazy {
        val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val found = mutableListOf<Pair<String, String>>()
        val singles = doc.getElementsByTagName("string")
        for (i in 0 until singles.length) {
            val e = singles.item(i) as Element
            found += e.getAttribute("name") to e.textContent
        }
        val items = doc.getElementsByTagName("item")
        for (i in 0 until items.length) {
            val e = items.item(i) as Element
            val parent = e.parentNode as Element
            found += "${parent.getAttribute("name")}[${e.getAttribute("quantity")}]" to e.textContent
        }
        found
    }

    @Test
    fun `every percent sign is a numbered argument or an escaped percent`() {
        val bad = strings.filter { (_, text) ->
            text.contains('%') && !text.replace(Regex("%%|%[0-9]+[$][-#+ 0,(]*[0-9]*([.][0-9]+)?[sdfx]"), "").let { !it.contains('%') }
        }
        assertTrue("Unformattable strings: $bad", bad.isEmpty())
    }

    @Test
    fun `arguments are never mixed between numbered and unnumbered`() {
        val bad = strings.filter { (_, text) -> Regex("%[sd]").containsMatchIn(text) }
        assertTrue("Unnumbered arguments (break in languages that reorder words): $bad", bad.isEmpty())
    }

    @Test
    fun `there are strings to check`() {
        assertTrue(strings.size > 100)
    }
}
