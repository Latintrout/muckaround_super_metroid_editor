package com.supermetroid.editor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Constraints
import com.supermetroid.editor.asm.*
import com.supermetroid.editor.data.ProjectRomBuildMode
import com.supermetroid.editor.data.SmEditProject
import com.supermetroid.editor.rom.RomParser
import com.supermetroid.editor.rom.TestRomHelper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Surface
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class AsmRoomSourceActionsTest {
    @TempDir lateinit var directory: File

    @Test fun `Source and Assets buttons open pure previews and adopt the selected room state without entering the visual editor`() = runBlocking {
        for (fromAsset in listOf(false, true)) {
            val parent = File(directory, "asset-$fromAsset").apply { mkdirs() }
            val src = File(parent, "reference/src").apply { mkdirs() }
            val data = File(parent, "reference/data").apply { mkdirs() }
            val rom = TestRomHelper.requireRomBytes()
            val parser = RomParser(rom)
            val asset = AsmAssetManifest.loadBundled().single { it.path == "LevelData_Flyway.bin" }
            File(data, asset.path).writeBytes(rom.copyOfRange(asset.pcOffset, asset.endExclusive))
            val macros = "macro PLMPopEntry(ID,X,Y,Param)\n dw <ID>\n db <X>,<Y>\n dw <Param>\nendmacro\n"
            File(src, "main.asm").writeText("incsrc macros.asm\nincsrc bank_8F.asm\nincsrc bank_B9..CE.asm\n")
            File(src, "macros.asm").writeText(macros)
            File(src, PLM_SOURCE_FILE).writeText("RoomHeader_Flyway: ;8F9879;\n db \$16\n")
            File(src, "bank_B9..CE.asm").writeText("LevelData_Flyway:\nincbin \"../data/LevelData_Flyway.bin\"\n")
            val reference = AsmReferenceWorkspace(src.parentFile,
                AsmReferenceMetadata(AsmReferenceContract.REPOSITORY_URL, AsmReferenceContract.COMMIT, "test", "base.sfc", "ab".repeat(32), rom.size, 1),
                AsmSourceParser().parse(src, data, listOf(asset)))
            val file = File(parent, "Test.smedit").apply { writeText("{}") }
            val repository = AsmProjectWorkspaceRepository(listOf(asset), emptyList())
            repository.initialize(file.path, reference)
            val workspace = AsmWorkspaceState(projectRepository = repository)
            workspace.bindProject(file.path, true)
            val project = SmEditProject("base.sfc").apply {
                asmWorkspace.enabled = true; asmWorkspace.buildMode = ProjectRomBuildMode.ASM_SOURCE
            }
            val editor = EditorState().apply { testMode = true; initForProject(file, project) }
            editor.bindRoomSources(AsmPlmSourceCatalog(File(src, PLM_SOURCE_FILE).readText(), macros, emptyMap(), parser, emptyMap()), workspace)
            val navigation = RoomAsmNavigation(editor, workspace, { "No GUI room is selected" })
            if (fromAsset) workspace.openAsset(asset.path) else workspace.openSource("bank_B9..CE.asm", 0)
            val before = Json.encodeToString(SmEditProject.serializer(), project)
            val revision = workspace.sourceBufferRevision
            val buildRequired = workspace.sourceBuildRequired
            val scene = ComposeScene()
            val surface = Surface.makeRasterN32Premul(1000, 800)
            try {
                scene.constraints = Constraints.fixed(1000, 800)
                scene.setContent { MaterialTheme {
                    AsmWorkspaceCanvas(workspace, parser, onOpenRoomSource = { navigation.openFromAsm(it) })
                } }
                var time = System.nanoTime()
                fun render() { repeat(12) { time += 16_666_667; scene.render(surface.canvas, time) } }
                render()
                click(scene, "Preview / make editable…"); render()
                click(scene, "Native state 2 · " + parser.parseRoomStatesWithData(0x9879)[1].stateInfo.conditionName); render()
                val preview = assertNotNull(workspace.roomSourceInspection, editor.statusMessage)
                assertEquals("state-2", preview.stateId)
                assertEquals(before, Json.encodeToString(SmEditProject.serializer(), project))
                assertEquals(revision, workspace.sourceBufferRevision); assertFalse(editor.dirty)
                assertEquals(buildRequired, workspace.sourceBuildRequired)
                assertFalse(workspace.hasProjectSourceChanges)
                assertFalse(workspace.isProjectSourceEditable)
                assertTrue(nodes(scene).none { it.config.contains(SemanticsProperties.EditableText) })
                click(scene, "Create editable source"); render()
                assertNull(workspace.roomSourceInspection, editor.statusMessage)
                assertEquals(1, project.rooms.getValue("9879").asmLayoutSourceVersion)
                assertTrue(workspace.isProjectSourceEditable); assertTrue(workspace.sourceBuildRequired)
                assertTrue(workspace.projectSourceText(PLM_SOURCE_FILE).contains("macro SmeditLayout_9879_"))
                assertEquals(0, editor.currentRoomId); assertNull(editor.roomSourceParser)
                assertTrue(editor.undoStack.isEmpty())
            } finally { scene.close(); surface.close() }
        }
    }

    @Test fun `room adoption controls are absent when project mode supplies no authoring callback`() {
        val scene = ComposeScene()
        val surface = Surface.makeRasterN32Premul(600, 200)
        try {
            scene.constraints = Constraints.fixed(600, 200)
            scene.setContent { MaterialTheme { AsmRoomSourceActions(listOf(AsmRoomSourceLink(0x9879, "Flyway", RoomAsmSourceKind.LAYOUT)), null) } }
            repeat(3) { scene.render(surface.canvas, System.nanoTime() + it * 16_666_667) }
            assertTrue(nodes(scene).none { text(it).contains("Preview / make editable") })
        } finally { scene.close(); surface.close() }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
    private fun nodes(scene: ComposeScene): List<SemanticsNode> = scene.roots.flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
    private fun text(node: SemanticsNode): String = node.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
    private fun click(scene: ComposeScene, label: String) {
        val button = nodes(scene).firstOrNull { it.config.getOrNull(SemanticsActions.OnClick)?.action != null && descendants(it).any { child -> text(child) == label } }
        assertNotNull(button, "Missing action: $label").config[SemanticsActions.OnClick].action!!.invoke()
    }
}
