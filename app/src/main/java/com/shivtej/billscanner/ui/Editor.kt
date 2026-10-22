package com.shivtej.billscanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.shivtej.billscanner.Container
import com.shivtej.billscanner.container
import com.shivtej.billscanner.core.model.Invoice
import kotlinx.coroutines.launch

/** Saves each change straight away and remembers the earlier versions for Undo (this session). */
class InvoiceEditor(private val c: Container, val invoice: Invoice?, private val bump: () -> Unit) {
    val canUndo: Boolean get() = invoice != null && (undo[invoice.id]?.isNotEmpty() == true)

    fun change(f: (Invoice) -> Invoice) {
        val old = invoice ?: return
        val new = f(old)
        if (new == old) return
        undo.getOrPut(old.id) { ArrayDeque() }.addLast(old)
        c.scope.launch { c.repo.save(new) }
        bump()
    }

    fun undo() {
        val inv = invoice ?: return
        val prev = undo[inv.id]?.removeLastOrNull() ?: return
        c.scope.launch { c.repo.save(prev) }
        bump()
    }

    companion object {
        private val undo = HashMap<String, ArrayDeque<Invoice>>()
    }
}

@Composable
fun rememberEditor(id: String): InvoiceEditor {
    val c = LocalContext.current.container
    val inv by remember(id) { c.repo.watch(id) }.collectAsState(initial = null)
    var tick by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_EXPRESSION") tick
    return InvoiceEditor(c, inv) { tick++ }
}
