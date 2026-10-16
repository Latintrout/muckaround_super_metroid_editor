package com.supermetroid.editor.asm

import com.supermetroid.editor.rom.RomFreeSpaceAllocator

/** One inline selector macro body, with a stable symbol derived from its room/state identity. */
internal data class AsmManagedRoomPredicate(val macroName: String) {
    val label = "${macroName}_Predicate"

    init {
        require(macroName.matches(Regex("SmeditSelectors_[0-9A-F]{4}_Condition_[0-9a-f]+"))) {
            "Managed predicates belong in a room's condition macro, not its order or another declaration."
        }
    }

    fun validateBody(body: String) {
        val lines = body.lineSequence().toList()
        require(lines.count { it.trim() == AsmRoomSelectorsSource.MANAGED_PREDICATE_MARKER } == 1 &&
            lines.none { it.trim() in setOf(AsmRoomSelectorsSource.ASM_PREDICATE_MARKER, AsmSelectorPredicateRecognizer.MARKER) }) {
            "$macroName: choose exactly one managed, fixed-address, or conversion predicate marker."
        }
        var statements = 0
        for (line in lines) {
            var code = asmCodeBeforeComment(line).trim()
            if (code.isEmpty()) continue
            if (code.matches(Regex("\\.+[A-Za-z_][A-Za-z0-9_]*:?"))) continue
            Regex("^\\.+[A-Za-z_][A-Za-z0-9_]*:[ \\t]*").find(code)?.let { code = code.substring(it.range.last + 1) }
            val match = Regex("([A-Za-z]+)(?:\\.[bwlBWL])?(?:[ \\t]+([^:\"{}]*))?").matchEntire(code)
            val operation = match?.groupValues?.get(1)?.uppercase()
            require(operation in AsmInstructionReference.instructions || operation in setOf("DB", "DW", "DL", "DD", "FILL", "FILLBYTE")) {
                "$macroName: managed bodies accept instructions, local labels and data/fill only, one per line. " +
                    "Placement directives, macro calls and includes require the fixed-address ASM workflow."
            }
            if (operation in setOf("FILL", "FILLBYTE")) {
                val value = AsmPlmDocument.literal(match!!.groupValues[2].trim())
                require(value != null && value in 0..if (operation == "FILL") 1024 else 255) {
                    "$macroName: $operation needs a bounded literal."
                }
            }
            statements++
        }
        require(statements > 0) { "$macroName: managed predicate body is empty." }
    }
}

/** Build-only placement; no addresses or generated labels are written into authored source. */
internal object AsmManagedRoomPredicates {
    fun render(predicates: List<AsmManagedRoomPredicate>, start: Int?): String = buildString {
        if (predicates.isEmpty()) return@buildString
        appendLine("; SMEDIT managed room predicates. Generated; edit the original condition macro body.")
        if (start == null) {
            // The discovery pass assembles authored source without emitting managed code.
            // Symbols keep raw authored references linkable while we find genuinely available space.
            predicates.forEach {
                appendLine("${it.label} = \$8F8000")
                appendLine("${it.label}_End = \$8F8001")
            }
        } else {
            require(start in 0x8F8000..0x8FFFFF) { "Managed predicates must be placed in bank \$8F." }
            appendLine("pushpc")
            appendLine("org \$${start.toString(16).uppercase()}")
            predicates.forEach {
                appendLine("${it.label}:")
                appendLine("fillbyte \$FF")
                appendLine("    %${it.macroName}()")
                appendLine("${it.label}_End:")
                appendLine("assert ${it.label}_End > ${it.label}, \"Managed predicate is empty\"")
                appendLine("assert ${it.label}_End-${it.label} <= 1024, \"Managed predicate exceeds 1024 bytes\"")
                appendLine("warnpc \$900000")
            }
            appendLine("pullpc")
        }
    }

    fun chooseStart(reference: ByteArray, authored: ByteArray, symbols: ByteArray,
                    predicates: List<AsmManagedRoomPredicate>): Int {
        require(reference.size == authored.size)
        val labels = AsmWlaSymbolParser.parse(symbols.toString(Charsets.UTF_8)).labels
        val managedLabels = predicates.map { it.label }.toSet()
        // Reserve complete manually declared code envelopes too, including their unchanged FF bytes.
        val excluded = labels.mapNotNull { (label, start) ->
            val end = labels[label + "_End"]
            if (label !in managedLabels && start in 0x8F8000..0x8FFFFF && end != null && end in (start + 1)..0x900000)
                snesLoRomToPc(start)!! until (snesLoRomToPc(start)!! + end - start) else null
        }
        val available = authored.copyOf()
        val bankStart = snesLoRomToPc(0x8F8000)!!
        val bankEnd = snesLoRomToPc(0x8FFFFF)!! + 1
        require(bankEnd <= available.size) { "ROM has no bank \$8F for managed predicates." }
        for (pc in bankStart until bankEnd) if (reference[pc].toInt() and 255 != 255) available[pc] = 0
        val allocator = RomFreeSpaceAllocator(available, { snesLoRomToPc(it) ?: -1 },
            { pcToSnesLoRom(it) ?: -1 }, excludedRanges = excluded)
        return requireNotNull(allocator.reserve(1, listOf(0x8F), "Managed room predicates")) {
            "No verified trailing free space in bank \$8F for managed room predicates. No ROM was produced."
        }.snesAddress
    }

    fun claims(predicates: List<AsmManagedRoomPredicate>, start: Int, reference: ByteArray,
               rom: ByteArray, symbols: ByteArray): List<AsmSourceOwnedRange> {
        val labels = AsmWlaSymbolParser.parse(symbols.toString(Charsets.UTF_8)).labels
        var expectedStart = start
        return predicates.map { predicate ->
            val address = requireNotNull(labels[predicate.label]) { "Missing managed predicate ${predicate.label}." }
            val end = requireNotNull(labels[predicate.label + "_End"]) { "Missing managed predicate boundary." }
            require(address == expectedStart && end > address && end - address <= 1024 && end <= 0x900000) {
                "${predicate.macroName}: managed predicate escaped its bank/size/placement bounds."
            }
            val pc = snesLoRomToPc(address)!!
            val size = end - address
            require(pc + size <= reference.size && pc + size <= rom.size &&
                (pc until pc + size).all { reference[it].toInt() and 255 == 255 }) {
                "${predicate.macroName}: managed placement would overwrite existing reference-ROM data."
            }
            expectedStart = end
            AsmSourceOwnedRange(pc, size, PLM_SOURCE_FILE)
        }
    }
}
