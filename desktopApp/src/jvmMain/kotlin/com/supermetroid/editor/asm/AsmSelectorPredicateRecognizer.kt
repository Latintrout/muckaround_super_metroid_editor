package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectRoomStateCondition
import com.supermetroid.editor.data.ProjectRoomStateConditionKind as Kind
import com.supermetroid.editor.rom.flattened
import com.supermetroid.editor.rom.projectRoomStateCondition

/**
 * A deliberately small, side-effect-free 16-bit predicate language, not an Asar
 * interpreter. Recognition is only offered for an explicitly marked draft. It
 * never makes that draft buildable: the user must review and convert it first.
 * Track A, Z and C independently; in particular LDA/BIT do NOT overwrite carry.
 */
internal object AsmSelectorPredicateRecognizer {
    const val MARKER = "; smedit:predicate-v1"
    private data class Instruction(val op: String, val operand: String, val line: Int)
    private data class Word(val address: Int? = null, val constant: Int? = null, val mask: Int = 0xFFFF)
    private data class Registers(val a: Word? = null, val z: Bool? = null, val c: Bool? = null)
    private sealed interface Bool {
        val nodes: Int
        data class Constant(val value: Boolean) : Bool { override val nodes = 1 }
        data class Test(val condition: ProjectRoomStateCondition) : Bool { override val nodes = 1 }
        data class And(val left: Bool, val right: Bool) : Bool { override val nodes = left.nodes + right.nodes + 1 }
        data class Or(val left: Bool, val right: Bool) : Bool { override val nodes = left.nodes + right.nodes + 1 }
    }
    private val yes = Bool.Constant(true)
    private val no = Bool.Constant(false)
    private fun complements(a: Bool, b: Bool) = a is Bool.Test && b is Bool.Test &&
        a.condition == b.condition.copy(negated = !b.condition.negated)
    private fun budget(a: Bool, b: Bool) = require(a.nodes + b.nodes + 1 <= 512) { "Intermediate expression exceeds the recognition budget." }
    private fun not(b: Bool): Bool = when (b) {
        is Bool.Constant -> Bool.Constant(!b.value)
        is Bool.Test -> Bool.Test(b.condition.copy(negated = !b.condition.negated))
        is Bool.And -> or(not(b.left), not(b.right))
        is Bool.Or -> and(not(b.left), not(b.right))
    }
    private fun and(a: Bool, b: Bool): Bool = when {
        a == no || b == no || complements(a, b) -> no
        a == yes || a == b -> b
        b == yes -> a
        else -> { budget(a, b); Bool.And(a, b) }
    }
    private fun or(a: Bool, b: Bool): Bool = when {
        a == yes || b == yes || complements(a, b) -> yes
        a == no || a == b -> b
        b == no -> a
        else -> { budget(a, b); Bool.Or(a, b) }
    }
    private fun choose(test: Bool, taken: Bool, other: Bool): Bool = when {
        taken == other -> taken
        taken == yes -> or(test, other)
        taken == no -> and(not(test), other)
        other == yes -> or(not(test), taken)
        other == no -> and(test, taken)
        else -> or(and(test, taken), and(not(test), other))
    }
    // RAM definitions in the pinned disassembly's memory.asm. Numeric absolute
    // addresses only: no symbol evaluation, direct-page assumptions or MMIO.
    private val masks = mapOf(0x09A2 to Kind.EQUIPMENT_EQUIPPED, 0x09A4 to Kind.EQUIPMENT_COLLECTED,
        0x09A6 to Kind.BEAM_EQUIPPED, 0x09A8 to Kind.BEAM_COLLECTED)
    private val thresholds = mapOf(0x09C2 to Kind.CURRENT_ENERGY_AT_LEAST, 0x09C4 to Kind.ENERGY_CAPACITY_AT_LEAST,
        0x09C6 to Kind.CURRENT_MISSILES_AT_LEAST, 0x09C8 to Kind.MISSILE_CAPACITY_AT_LEAST,
        0x09CA to Kind.CURRENT_SUPER_MISSILES_AT_LEAST, 0x09CC to Kind.SUPER_MISSILE_CAPACITY_AT_LEAST,
        0x09CE to Kind.CURRENT_POWER_BOMBS_AT_LEAST, 0x09D0 to Kind.POWER_BOMB_CAPACITY_AT_LEAST,
        0x09D4 to Kind.RESERVE_CAPACITY_AT_LEAST, 0x09D6 to Kind.CURRENT_RESERVE_ENERGY_AT_LEAST)
    private fun test(kind: Kind, argument: Int) = Bool.Test(projectRoomStateCondition(kind, argument))
    private fun nonzero(w: Word): Bool? {
        w.constant?.let { return if (it != 0) yes else no }
        if (w.mask == 0) return no
        masks[w.address]?.let { kind ->
            return (0..15).filter { w.mask and (1 shl it) != 0 }.map { test(kind, 1 shl it) }.reduce(::or)
        }
        return thresholds[w.address]?.takeIf { w.mask == 0xFFFF }?.let { test(it, 1) }
    }
    private fun atLeast(w: Word, value: Int): Bool? {
        w.constant?.let { return if (it >= value) yes else no }
        if (value == 0) return yes
        return thresholds[w.address]?.takeIf { w.mask == 0xFFFF }?.let { test(it, value) }
    }
    private fun equal(w: Word, value: Int): Bool? {
        w.constant?.let { return if (it == value) yes else no }
        if (value and w.mask.inv() and 0xFFFF != 0) return no
        if (value == 0) return nonzero(w)?.let(::not)
        masks[w.address]?.let { kind ->
            return (0..15).filter { w.mask and (1 shl it) != 0 }.map { bit ->
                test(kind, 1 shl bit).let { if (value and (1 shl bit) != 0) it else not(it) }
            }.reduce(::and)
        }
        val lower = atLeast(w, value) ?: return null
        val upper = if (value == 0xFFFF) no else atLeast(w, value + 1) ?: return null
        return and(lower, not(upper))
    }

    fun recognize(body: String): ProjectRoomStateCondition {
        require(body.lineSequence().count { it.trim() == MARKER } == 1) { "Expected exactly one $MARKER declaration." }
        val instructions = mutableListOf<Instruction>()
        val labels = mutableMapOf<String, Int>()
        fun literal(s: String) = requireNotNull(AsmPlmDocument.literal(s)?.takeIf { it in 0..0xFFFF }) { "Expected a literal 16-bit operand, got '$s'." }
        body.lineSequence().forEachIndexed { index, line ->
            var code = line.substringBefore(';').trim()
            if (code.isEmpty()) return@forEachIndexed
            Regex("^(\\.[A-Za-z_][A-Za-z0-9_]*):").find(code)?.let {
                require(labels.put(it.groupValues[1], instructions.size) == null) { "Duplicate local label on predicate line ${index + 1}." }
                code = code.substring(it.range.last + 1).trim()
            }
            if (code.isEmpty()) return@forEachIndexed
            val match = requireNotNull(Regex("^([A-Za-z]+(?:\\.[A-Za-z]+)?)(?:\\s+(.+))?$").matchEntire(code)) { "Unsupported predicate statement on line ${index + 1}." }
            val op = match.groupValues[1].lowercase()
            val operand = match.groupValues[2]
            when (op) {
                "lda.w" -> if (operand.startsWith('#')) literal(operand.drop(1)) else {
                    val address = literal(operand)
                    require(address in masks || address in thresholds) { "Unsupported RAM read on predicate line ${index + 1}." }
                }
                "and.w", "bit.w", "cmp.w" -> { require(operand.startsWith('#')) { "Expected an immediate operand on predicate line ${index + 1}." }; literal(operand.drop(1)) }
                "beq", "bne", "bcc", "bcs", "bra", "jmp" -> require(operand.matches(Regex("\\.[A-Za-z_][A-Za-z0-9_]*"))) { "Only named local branch targets are supported." }
                "clc", "sec", "rts", "nop" -> require(operand.isEmpty()) { "Unexpected operand on predicate line ${index + 1}." }
                else -> error("Unsupported instruction '$op' on predicate line ${index + 1}; custom code is not converted.")
            }
            instructions += Instruction(op, operand, index + 1)
            require(instructions.size <= 64) { "Predicate exceeds 64 instructions." }
        }
        require(instructions.isNotEmpty()) { "Empty predicate." }
        val targets = instructions.map { i ->
            if (i.op in setOf("beq", "bne", "bcc", "bcs", "bra", "jmp"))
                requireNotNull(labels[i.operand]) { "Unknown local target '${i.operand}' on predicate line ${i.line}." } else null
        }
        val offsets = instructions.runningFold(0) { offset, i -> offset + when (i.op) {
            "lda.w", "and.w", "bit.w", "cmp.w", "jmp" -> 3
            "beq", "bne", "bcc", "bcs", "bra" -> 2
            else -> 1
        } }
        instructions.forEachIndexed { index, i ->
            if (targets[index] != null && i.op != "jmp") require(offsets[targets[index]!!] - offsets[index + 1] in -128..127) {
                "Relative branch exceeds its signed byte range on predicate line ${i.line}."
            }
        }
        // Check the entire control-flow graph, including branches whose test may
        // turn out constant. Neither loops nor fall-through are guessed away.
        val visiting = mutableSetOf<Int>()
        val visited = mutableSetOf<Int>()
        fun check(pc: Int) {
            require(pc in instructions.indices) { "Predicate falls through without RTS." }
            require(pc !in visiting) { "Predicate contains a loop." }
            if (!visited.add(pc)) return
            visiting += pc
            val op = instructions[pc].op
            if (op != "rts") {
                if (targets[pc] != null) check(targets[pc]!!)
                if (op !in setOf("bra", "jmp")) check(pc + 1)
            }
            visiting -= pc
        }
        check(0)
        require(visited.size == instructions.size) { "Predicate contains unreachable instructions; review them before conversion." }
        var steps = 0
        fun run(pc: Int, r: Registers): Bool {
            require(++steps <= 512) { "Predicate control flow exceeds the recognition budget." }
            val i = instructions[pc]
            fun accumulator() = requireNotNull(r.a) { "A is unknown on predicate line ${i.line}." }
            fun flag(b: Bool?) = requireNotNull(b) { "Required flag is unknown on predicate line ${i.line}." }
            fun next(n: Registers) = run(pc + 1, n)
            val v by lazy { literal(i.operand.removePrefix("#")) }
            return when (i.op) {
                "lda.w" -> {
                    val w = if (i.operand.startsWith('#')) Word(constant = v) else Word(address = v)
                    next(r.copy(a = w, z = nonzero(w)?.let(::not)))
                }
                "and.w" -> {
                    val old = accumulator()
                    val w = old.copy(constant = old.constant?.and(v), mask = old.mask and v)
                    next(r.copy(a = w, z = nonzero(w)?.let(::not)))
                }
                "bit.w" -> {
                    val old = accumulator()
                    next(r.copy(z = nonzero(old.copy(constant = old.constant?.and(v), mask = old.mask and v))?.let(::not)))
                }
                "cmp.w" -> next(r.copy(z = equal(accumulator(), v), c = atLeast(accumulator(), v)))
                "sec" -> next(r.copy(c = yes))
                "clc" -> next(r.copy(c = no))
                "nop" -> next(r)
                "rts" -> flag(r.c)
                "bra", "jmp" -> run(targets[pc]!!, r)
                else -> {
                    val test = when (i.op) { "beq" -> flag(r.z); "bne" -> not(flag(r.z)); "bcs" -> flag(r.c); else -> not(flag(r.c)) }
                    choose(test, run(targets[pc]!!, r), run(pc + 1, r))
                }
            }
        }
        var nodes = 0
        fun condition(b: Bool, depth: Int): ProjectRoomStateCondition {
            require(depth <= 8 && ++nodes <= 64) { "Recognized expression exceeds 8 levels or 64 nodes." }
            return when (b) {
                is Bool.Constant -> projectRoomStateCondition(Kind.NEVER, negated = b.value)
                is Bool.Test -> b.condition
                is Bool.And, is Bool.Or -> {
                    val kind = if (b is Bool.And) Kind.ALL_OF else Kind.ANY_OF
                    fun operands(value: Bool): List<Bool> = when {
                        value is Bool.And && kind == Kind.ALL_OF -> operands(value.left) + operands(value.right)
                        value is Bool.Or && kind == Kind.ANY_OF -> operands(value.left) + operands(value.right)
                        else -> listOf(value)
                    }
                    projectRoomStateCondition(kind, children = operands(b).map { condition(it, depth + 1) })
                }
            }.also { require(it.flattened().size <= 64) { "Recognized expression exceeds 64 nodes." } }
        }
        return condition(run(0, Registers()), 0)
    }
}
