// The screens contract's labels, carried over from Mandarin Remote (where tools/quality/parity.js wrote them from its docs/specs/screens.json); in Mallard they are edited by hand.
package com.musicd.mallard.client

/**
 * The screens contract's labels (docs/specs/native-parity.md): what each
 * native control is called, aloud too, one per state, as the page calls it.
 */
object Contract {
    val labels: Map<String, List<String>> = ContractLabels1.labels + ContractLabels2.labels

    private val blank = Regex("\\{[^}]+\\}")

    /**
     * [key]'s label in [state], each {…} in it filled from [fill] in turn. Asked on every frame a control is drawn: one
     * pattern made once, and a label with nothing to fill given as it is.
     */
    fun label(key: String, state: Int = 0, vararg fill: String): String {
        val all = labels[key] ?: throw IllegalArgumentException("$key: not a control of the contract")
        val said = all[state.coerceIn(0, all.size - 1)]
        if ('{' !in said) return said
        var i = 0
        return blank.replace(said) { fill.getOrElse(i++) { "" } }
    }
}
