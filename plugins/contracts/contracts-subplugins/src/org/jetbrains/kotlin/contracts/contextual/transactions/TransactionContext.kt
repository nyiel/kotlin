/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.contracts.contextual.transactions

import org.jetbrains.kotlin.contracts.contextual.cfg.ContextContracts
import org.jetbrains.kotlin.contracts.contextual.model.Context
import org.jetbrains.kotlin.contracts.description.InvocationKind
import org.jetbrains.kotlin.descriptors.ValueDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticSink
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.resolve.source.PsiSourceElement

internal data class TransactionContext(val openedTransactions: Map<ValueDescriptor, InvocationKind> = mapOf()) : Context {
    override val family = TransactionFamily

    override fun reportRemaining(sink: DiagnosticSink, declaredContracts: ContextContracts) {
        for ((value, kind) in openedTransactions) {
            if (kind != InvocationKind.ZERO) {
                val message = "Transaction ${value.name} must be closed"
                val source = (value.source as? PsiSourceElement)?.psi ?: throw AssertionError()
                sink.report(Errors.CONTEXTUAL_EFFECT_WARNING.on(source, message))
            }
        }
    }
}