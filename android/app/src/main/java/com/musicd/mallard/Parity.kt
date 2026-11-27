package com.musicd.mallard

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * A native screen's item, by its key in the screens contract
 * (docs/specs/screens.json, docs/specs/native-parity.md): the gate
 * (tools/quality/parity.js) reads these from the source and holds each
 * native screen to the page's. A control's [label] is the contract's
 * (Contract.label), read aloud as the page's is.
 */
internal fun Modifier.parity(key: String, label: String? = null): Modifier =
    testTag(key).let { m -> if (label == null) m else m.semantics { contentDescription = label } }
