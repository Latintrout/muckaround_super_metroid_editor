package com.supermetroid.editor.asm

import com.supermetroid.editor.data.ProjectPlmSourceBinding
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import kotlinx.serialization.Serializable
import com.supermetroid.editor.rom.baseSourceStateIndex
import java.io.File

internal const val PLM_SOURCE_FILE = "bank_8F.asm"

internal data class AsmPlmConsumer(val roomId: Int, val roomName: String, val stateIndex: Int, val statePc: Int)

/** Immutable source identities and verified original layouts. No editable data is stored here. */
internal class AsmPlmSourceCatalog(
    val originalText: String,
    private val originalMacros: String,
    val symbols: Map<String, Int>,
    val parser: RomParser,
    val consumers: Map<Int, List<AsmPlmConsumer>>,
    val sourceFingerprint: String = AsmVerifiedCache.fingerprint(mapOf(
        "source" to originalText, "macros" to originalMacros, "symbols" to symbols.toSortedMap().toString(),
    )),
) {
    val romFingerprint = AsmVerifiedCache.hash(parser.getRomData())
    private val bindings = mutableMapOf<Int, ProjectPlmSourceBinding>()
    private val originalSource = AsmPlmDocument.Companion.Source(originalText)
    private val originalDocuments = mutableMapOf<Int, AsmPlmDocument>()
    private var verifiedEnemyMacroText: String? = null

    fun requireStandardMacro(macros: String) {
        require(plmMacro(macros) == plmMacro(originalMacros)) { "%PLMPopEntry has a custom layout; standard room-object source is unavailable." }
    }
    /** Native record layouts cannot be widened just because Asar accepts the new byte stream. */
    fun requireStandardEnemyMacros(macros: String) {
        if (verifiedEnemyMacroText == macros) return
        for (name in listOf("EnemyPopulations", "EnemyHeader", "enemyID", "XPosition", "YPosition", "initParam", "properties", "extraProperties", "speedParams")) {
            val pattern = Regex("(?ms)^macro $name\\([^\\n]*\\).*?^endmacro\\b")
            val original = pattern.findAll(originalMacros).toList()
            val current = pattern.findAll(macros).toList()
            fun code(value: String) = value.lineSequence().map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            require(original.size <= 1 && current.size == original.size &&
                original.singleOrNull()?.value?.let(::code) == current.singleOrNull()?.value?.let(::code)) {
                val line = current.firstOrNull()?.range?.first?.let { macros.take(it).count { c -> c == '\n' } } ?: 0
                "macros.asm:${line + 1}: %$name has a custom record layout. Authored enemy data requires the native layout; restore it or use a matching codec."
            }
        }
        verifiedEnemyMacroText = macros
    }
    fun binding(pointer: Int): ProjectPlmSourceBinding {
        bindings[pointer]?.let { return it }
        require(pointer in 0x8000..0xFFFF && consumers[pointer].orEmpty().isNotEmpty()) { "No existing room owns this PLM list." }
        val label = originalSource.labelAtPointer(pointer)
            ?: error("No exact source declaration for this object list. Keep using ROM edits for now.")
        val original = originalSource.parse(label, symbols)
        require(original.entries == parser.parsePlmSet(pointer)) {
            "$label does not match the base ROM. This list cannot be linked safely."
        }
        originalDocuments[pointer] = original
        return ProjectPlmSourceBinding(label, pointer, original.entries.size).also { bindings[pointer] = it }
    }

    fun document(binding: ProjectPlmSourceBinding, source: String, macros: String): AsmPlmDocument {
        require(binding == binding(binding.pointer)) { "The linked PLM identity/layout no longer matches the original source." }
        require(plmMacro(macros) == plmMacro(originalMacros)) { "%PLMPopEntry has a custom layout; visual PLM editing is unavailable." }
        val result = AsmPlmDocument.parse(source, binding.label, symbols)
        require(result.entries.size == binding.entryCount) {
            "${binding.label}: linked tables currently keep ${binding.entryCount} records. Adding/removing records requires relocation support."
        }
        return result
    }

    fun isOriginal(binding: ProjectPlmSourceBinding, document: AsmPlmDocument): Boolean =
        document.entries == parser.parsePlmSet(binding.pointer)

    fun validateOwnership(project: SmEditProject, binding: ProjectPlmSourceBinding) {
        require(project.asmWorkspace.enabled && project.asmWorkspace.buildMode == ProjectRomBuildMode.ASM_SOURCE) {
            "Linked object source requires ASM source mode."
        }
        require(guiOwner(project, binding.pointer) == null) {
            "${guiOwner(project, binding.pointer)}. These objects are managed by the room editor; the unchanged ASM list is a baseline, not a competing edit."
        }
    }

    /** Includes all consumers: taking over a shared list must not hide a sibling's state-local edits. */
    fun guiOwner(project: SmEditProject, pointer: Int): String? {
        for (consumer in consumers[pointer].orEmpty()) {
            val edits = project.rooms[project.roomKey(consumer.roomId)] ?: continue
            if (edits.asmPlmSourceVersion != 0) return "${consumer.roomName} uses relocated project object source"
            if (edits.stateGraphChanged) return "${consumer.roomName} has a room-editor state graph"
            if (edits.plmChanges.isNotEmpty()) return "${consumer.roomName} has room-wide object edits"
            if (edits.customScrollCommands.isNotEmpty()) return "${consumer.roomName} has room-editor scroll commands"
            if (edits.states.any { it.baseSourceStateIndex() == consumer.stateIndex &&
                    (it.plmChanges.isNotEmpty() || it.customScrollCommands.isNotEmpty() || it.resourcesChanged) })
                return "${consumer.roomName} has state-specific object or resource edits"
        }
        return null
    }

    /** A read-only, revision-local lens cache. Ownership itself is evaluated against current project edits. */
    inner class SourceRevision(source: String, private val macros: String) {
        private val index = AsmPlmDocument.Companion.Source(source)
        private val documents = mutableMapOf<Int, Result<AsmPlmDocument>>()
        fun authority(project: SmEditProject, pointer: Int): AsmPlmAuthorityView {
            val explicit = project.asmWorkspace.plmSourceBindings.firstOrNull { it.pointer == pointer }
            val identity = runCatching { binding(pointer) }.getOrElse {
                return AsmPlmAuthorityView(if (explicit == null) AsmPlmAuthority.UNAVAILABLE else AsmPlmAuthority.CONFLICT,
                    explanation = it.message)
            }
            val result = documents.getOrPut(pointer) { runCatching {
                require(plmMacro(macros) == plmMacro(originalMacros)) {
                    "%PLMPopEntry has a custom layout. Restore its six-byte layout before using the standard room-object editor."
                }
                index.parse(identity.label, symbols)
            } }
            val document = result.getOrElse {
                return AsmPlmAuthorityView(AsmPlmAuthority.CONFLICT, explanation = "${identity.label}: ${it.message}")
            }
            val sourceChanged = document.entries != originalDocuments.getValue(pointer).entries
            val owner = guiOwner(project, pointer)
            if (owner != null) {
                if (sourceChanged || explicit != null) return AsmPlmAuthorityView(AsmPlmAuthority.CONFLICT,
                    explanation = "${identity.label}: $owner and an ASM-owned object list. Both versions are retained; resolve their ownership before building.")
                return AsmPlmAuthorityView(AsmPlmAuthority.ROOM_EDITOR, explanation =
                    "$owner. Room edits are included automatically when building; the original ASM list is only the baseline.")
            }
            if (document.entries.size != identity.entryCount) return AsmPlmAuthorityView(AsmPlmAuthority.CONFLICT,
                explanation = "${identity.label}: ASM list size changed from ${identity.entryCount} to ${document.entries.size}. " +
                    "Source-list relocation is not supported yet. Use the room editor for additions/removals; no source text was discarded.")
            if (explicit != null && explicit != identity) return AsmPlmAuthorityView(AsmPlmAuthority.CONFLICT,
                explanation = "${identity.label}: saved source identity/layout no longer matches the base ROM.")
            return AsmPlmAuthorityView(if (sourceChanged || explicit != null) AsmPlmAuthority.SOURCE else AsmPlmAuthority.UNCHANGED, document)
        }
    }

    /** Also used headlessly: correctness must not depend on visiting Room Info or clicking Link. */
    fun sourceBindings(project: SmEditProject, source: String, macros: String): List<ProjectPlmSourceBinding> {
        require(project.asmWorkspace.plmSourceBindings.map { it.pointer }.distinct().size == project.asmWorkspace.plmSourceBindings.size) {
            "Duplicate PLM source bindings."
        }
        val revision = SourceRevision(source, macros)
        return (consumers.keys + project.asmWorkspace.plmSourceBindings.map { it.pointer }).distinct().mapNotNull { pointer ->
            val view = revision.authority(project, pointer)
            require(view.authority != AsmPlmAuthority.CONFLICT) { view.explanation.orEmpty() }
            if (view.authority == AsmPlmAuthority.SOURCE) binding(pointer) else null
        }
    }

    /** Prove the live lens describes the assembler's output, and protect the whole resource, not just changed bytes. */
    fun protectBuild(project: SmEditProject, source: String, macros: String, compiled: AsmCompiledBuildBase): AsmCompiledBuildBase {
        val bindings = sourceBindings(project, source, macros)
        val additions = mutableListOf<AsmSourceOwnedRange>()
        val built = RomParser(compiled.romBytes)
        val compiledLabels = AsmWlaSymbolParser.parse(compiled.compilerSymbols.toString(Charsets.UTF_8)).labels
        for (binding in bindings) {
            validateOwnership(project, binding)
            val document = document(binding, source, macros)
            require(compiledLabels[binding.label] == (0x8F0000 or binding.pointer)) {
                "${binding.label}: compiled source address moved or disappeared. The fixed-layout link cannot be used."
            }
            require(built.parsePlmSet(binding.pointer) == document.entries) {
                "${binding.label}: compiled bytes differ from the linked source model (layout, symbol, address, or custom module changed). No ROM was exported."
            }
            val offset = checkNotNull(snesLoRomToPc(0x8F0000 or binding.pointer))
            val spans = mutableListOf(offset until offset + binding.entryCount * 6 + 2)
            for (consumer in consumers[binding.pointer].orEmpty()) {
                val stateOffsets = built.findAllStateDataOffsets(consumer.roomId)
                val originalHeader = if (parser.getRomData().size % 0x8000 == 0x200) 0x200 else 0
                val builtHeader = if (compiled.romBytes.size % 0x8000 == 0x200) 0x200 else 0
                require(stateOffsets.getOrNull(consumer.stateIndex)?.minus(builtHeader) == consumer.statePc - originalHeader &&
                    built.readStateData(stateOffsets[consumer.stateIndex])["plmSetPtr"] == binding.pointer) {
                    "${binding.label}: ${consumer.roomName}'s source state reference moved or changed. Relink support is required."
                }
                val pointerPc = consumer.statePc - originalHeader + 20
                spans += pointerPc until pointerPc + 2
            }
            // Existing changed-source ranges already have ownership. Claim only uncovered bytes.
            for (span in spans) {
                var start: Int? = null
                for (pc in span.first..span.last + 1) {
                    val uncovered = pc <= span.last && (compiled.sourceOwnedRanges + additions).none {
                        pc >= it.pcOffset && pc < it.pcOffset + it.length
                    }
                    if (uncovered && start == null) start = pc
                    if (!uncovered && start != null) {
                        additions += AsmSourceOwnedRange(start, pc - start, PLM_SOURCE_FILE)
                        start = null
                    }
                }
            }
        }
        return compiled.copy(sourceOwnedRanges = compiled.sourceOwnedRanges + additions)
    }

    companion object {
        @Serializable private data class SymbolSnapshot(val symbols: Map<String, Int>)

        fun load(originalDirectory: File, parser: RomParser): AsmPlmSourceCatalog {
            val src = File(originalDirectory, "src")
            val texts = src.walkTopDown().filter { it.isFile && it.extension.equals("asm", true) }
                .associate { it.relativeTo(src).invariantSeparatorsPath to it.readText() }
            val fingerprint = AsmVerifiedCache.fingerprint(texts)
            val cache = AsmVerifiedCache(File(originalDirectory.parentFile, "projections"))
            val symbols = cache.loadOrBuild("plm-symbols", "symbols-v1:$fingerprint", SymbolSnapshot.serializer(),
                validate = { snapshot -> require(snapshot.symbols.all { it.key.isNotBlank() && it.value in 0..0xFFFF }) },
            ) {
                val index = AsmSourceParser().parse(src, File(originalDirectory, "data"), emptyList(), texts.keys, texts)
                SymbolSnapshot(index.labels.mapNotNull { label ->
                    index.addressAtlas.exactAt(label.fileId, label.lineIndex).firstOrNull()?.let {
                        label.name to (it.snesAddress and 0xFFFF)
                    }
                }.toMap())
            }.symbols
            val consumers = linkedMapOf<Int, MutableList<AsmPlmConsumer>>()
            for (room in parser.roomCatalog.rooms) {
                val roomId = room.getRoomIdAsInt()
                parser.findAllStateDataOffsets(roomId).forEachIndexed { stateIndex, pc ->
                    val pointer = parser.readStateData(pc)["plmSetPtr"] ?: return@forEachIndexed
                    consumers.getOrPut(pointer, ::mutableListOf) += AsmPlmConsumer(roomId, room.name, stateIndex, pc)
                }
            }
            return AsmPlmSourceCatalog(texts.getValue(PLM_SOURCE_FILE), texts.getValue("macros.asm"), symbols, parser, consumers, fingerprint)
        }

        private fun plmMacro(text: String): String = Regex("(?ms)^macro PLMPopEntry\\([^\\n]*\\).*?^endmacro\\b")
            .findAll(text).toList().singleOrNull()?.value ?: error("Expected one standard PLMPopEntry macro.")
    }
}
