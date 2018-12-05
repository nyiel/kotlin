/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen.optimization.fixLvt

import org.jetbrains.kotlin.codegen.optimization.common.asSequence
import org.jetbrains.kotlin.codegen.optimization.transformer.MethodTransformer
import org.jetbrains.org.objectweb.asm.Opcodes
import org.jetbrains.org.objectweb.asm.Type
import org.jetbrains.org.objectweb.asm.tree.*

class FixLvtMethodTransformer : MethodTransformer() {
    override fun transform(internalClassName: String, methodNode: MethodNode) {
        val clashingLocals = findLocalsOfDifferentTypeInSameSlot(methodNode)
        if (clashingLocals.all { it.isEmpty() }) return
        val newSlots = calculateNewSlotsAndUpdateMaxLocals(methodNode, clashingLocals)
        moveLocalsToNewSlots(methodNode, newSlots, clashingLocals)
    }

    private fun moveLocalsToNewSlots(
        methodNode: MethodNode,
        newSlots: Map<LocalVariableNode, Int>,
        clashingLocals: List<Collection<LocalVariableNode>>
    ) {
        for (insn in methodNode.instructions.asSequence()) {
            if (insn is IincInsnNode) {
                val local = findLocalOfInsn(methodNode, clashingLocals[insn.`var`], insn) ?: continue
                if (local.desc != "I" && local.desc != "Z") continue
                insn.`var` = newSlots[local] ?: continue
            } else if (insn is VarInsnNode) {
                val local = findLocalOfInsn(methodNode, clashingLocals[insn.`var`], insn) ?: continue
                // Do not mess with stack spilling
                val isCompatible = when (insn.opcode) {
                    Opcodes.ILOAD,
                    Opcodes.ISTORE -> local.desc == "I" || local.desc == "Z" || local.desc == "S" || local.desc == "C"
                    Opcodes.LLOAD,
                    Opcodes.LSTORE -> local.desc == "J"
                    Opcodes.FLOAD,
                    Opcodes.FSTORE -> local.desc == "F"
                    Opcodes.DLOAD,
                    Opcodes.DSTORE -> local.desc == "D"
                    Opcodes.ALOAD,
                    Opcodes.ASTORE -> local.desc[0] == 'L' || local.desc[0] == '['
                    else -> false
                }
                if (!isCompatible) continue
                insn.`var` = newSlots[local] ?: continue
            }
        }

        for ((local, slot) in newSlots) {
            local.index = slot
        }
    }

    private fun findLocalOfInsn(
        methodNode: MethodNode,
        clashingLocals: Collection<LocalVariableNode>,
        insn: AbstractInsnNode
    ): LocalVariableNode? {
        if (clashingLocals.isEmpty()) return null

        fun AbstractInsnNode.index() = methodNode.instructions.indexOf(this)

        val index = insn.index()
        for (local in clashingLocals) {
            if (local.start.index() < index && index < local.end.index()) {
                return local
            }
        }
        // For initial ASTORES, which are outside local variable's range, return closest range
        return clashingLocals.filter { index < it.start.index() }.minBy { it.start.index() }
    }

    private fun calculateNewSlotsAndUpdateMaxLocals(
        methodNode: MethodNode,
        clashingLocals: List<Collection<LocalVariableNode>>
    ): Map<LocalVariableNode, Int> {
        var newSlot = clashingLocals.size
        val res = hashMapOf<LocalVariableNode, Int>()
        val paramsSize = Type.getArgumentTypes(methodNode.desc).map { it.size }.fold(0, Int::plus) +
                if (methodNode.access and Opcodes.ACC_STATIC != 0) 0 else 1
        for (i in paramsSize until clashingLocals.size) {
            for (localToShift in clashingLocals[i].drop(1)) {
                res[localToShift] = newSlot
                newSlot += Type.getType(localToShift.desc).size
            }
        }
        methodNode.maxLocals = newSlot
        return res
    }

    private fun findLocalsOfDifferentTypeInSameSlot(methodNode: MethodNode): List<Collection<LocalVariableNode>> {
        val localsBySlot = List<MutableList<LocalVariableNode>>(methodNode.maxLocals) { arrayListOf() }
        // Fill with all locals
        for (localVariable in methodNode.localVariables) {
            localsBySlot[localVariable.index].add(localVariable)
        }
        for (i in localsBySlot.indices) {
            when {
                // Filter out singles
                localsBySlot[i].size <= 1 -> localsBySlot[i].clear()
                // Filter out locals with the same type
                localsBySlot[i].allTheSameType() -> localsBySlot[i].clear()
            }
        }
        return localsBySlot
    }

    private fun List<LocalVariableNode>.allTheSameType(): Boolean {
        assert(size != 0) { "Empty sets should be already filtered out" }
        val type = this.first().desc[0] // Regard all objects as the same type
        return all { it.desc[0] == type }
    }
}