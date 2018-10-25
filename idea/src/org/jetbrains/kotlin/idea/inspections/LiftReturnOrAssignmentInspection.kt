/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.BranchedFoldingUtils
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.isElseIf
import org.jetbrains.kotlin.idea.intentions.branchedTransformations.lineCount
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset

class LiftReturnOrAssignmentInspection : AbstractKotlinInspection() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean, session: LocalInspectionToolSession) =
        object : KtVisitorVoid() {
            private fun visitIfOrWhenOrTry(expression: KtExpression, keyword: PsiElement) {
                if (expression.lineCount() > LINES_LIMIT) return
                if (expression.isElseIf()) return

                val foldableReturns = BranchedFoldingUtils.getFoldableReturns(expression)
                if (foldableReturns?.isNotEmpty() == true) {
                    val hasOtherReturns = expression.anyDescendantOfType<KtReturnExpression> { it !in foldableReturns }
                    val isSerious = !hasOtherReturns && foldableReturns.size > 1
                    val verb = if (isSerious) "should" else "can"
                    val description = "Return $verb be lifted out of '${keyword.text}'"
                    holder.registerProblemWithoutOfflineInformation(
                        expression,
                        description,
                        isOnTheFly,
                        if (isSerious) ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                        else ProblemHighlightType.INFORMATION,
                        keyword.textRange?.shiftRight(-expression.startOffset),
                        LiftReturnOutFix(keyword.text)
                    )
                    foldableReturns.forEach {
                        holder.registerProblemWithoutOfflineInformation(
                            expression,
                            description,
                            isOnTheFly,
                            ProblemHighlightType.INFORMATION,
                            it.returnKeyword.textRange?.shiftRight(-expression.startOffset),
                            LiftReturnOutFix(keyword.text)
                        )
                    }
                    return
                }
                val assignmentNumber = BranchedFoldingUtils.getFoldableAssignmentNumber(expression)
                if (assignmentNumber > 0) {
                    val verb = if (assignmentNumber > 1) "should" else "can"
                    holder.registerProblemWithoutOfflineInformation(
                        expression,
                        "Assignment $verb be lifted out of '${keyword.text}'",
                        isOnTheFly,
                        if (assignmentNumber > 1) ProblemHighlightType.GENERIC_ERROR_OR_WARNING
                        else ProblemHighlightType.INFORMATION,
                        keyword.textRange?.shiftRight(-expression.startOffset),
                        LiftAssignmentOutFix(keyword.text)
                    )
                }
            }

            override fun visitIfExpression(expression: KtIfExpression) {
                super.visitIfExpression(expression)
                visitIfOrWhenOrTry(expression, expression.ifKeyword)
            }

            override fun visitWhenExpression(expression: KtWhenExpression) {
                super.visitWhenExpression(expression)
                visitIfOrWhenOrTry(expression, expression.whenKeyword)
            }

            override fun visitTryExpression(expression: KtTryExpression) {
                super.visitTryExpression(expression)
                expression.tryKeyword?.let {
                    visitIfOrWhenOrTry(expression, it)
                }
            }
        }

    private class LiftReturnOutFix(private val keyword: String) : LocalQuickFix {
        override fun getName() = "Lift return out of '$keyword'"

        override fun getFamilyName() = name

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val replaced = BranchedFoldingUtils.foldToReturn(descriptor.psiElement as KtExpression)
            replaced.findExistingEditor()?.caretModel?.moveToOffset(replaced.startOffset)
        }
    }

    private class LiftAssignmentOutFix(private val keyword: String) : LocalQuickFix {
        override fun getName() = "Lift assignment out of '$keyword'"

        override fun getFamilyName() = name

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            BranchedFoldingUtils.foldToAssignment(descriptor.psiElement as KtExpression)
        }
    }

    companion object {
        private const val LINES_LIMIT = 15
    }
}
