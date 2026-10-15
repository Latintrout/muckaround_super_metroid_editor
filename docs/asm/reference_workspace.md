# ASM Reference Workspace

SMEDIT's **ASM** tab begins with a deliberately read-only reference layer and can
now opt an editable project into an isolated **Project ASM** workspace. The
reference turns the exact annotated Super Metroid disassembly used by the parity
suite into an IDE-like knowledge browser without making either an ASM checkout
or a second ROM-selection step a prerequisite for ordinary ROM editing. Project
ASM establishes safe source ownership and editing and makes that source the build
base as soon as it is enabled. Ordinary projects remain on the existing loaded-ROM
patch path. A clean Project ASM workspace can temporarily return to **Loaded ROM**;
once a saved or unsaved source edit exists, source mode is required so the edit can
never be silently omitted from an export.

Editor-to-source **ASM** shortcuts appear only when **Project ASM** is enabled
and **ASM source** is the active build mode. This rule covers Room Info, selected
objects/doors/saves, scrolls, FX, tilesets, sprites, enemy stats, minimap, text and
sound. Downloading the reference alone does not enable these links; returning a
clean workspace to **Loaded ROM** hides them again. ROM edits can relocate data
without updating reference source, so those shortcuts must not imply a mapping in
patch-only projects. The main **ASM** tab, reference navigation and Library remain
available in either mode.

## User workflow

1. Open a ROM normally in SMEDIT.
2. Open the **ASM** tab and choose **Download ASM Reference**.
3. SMEDIT downloads the pinned `sm_disassembly` source revision and extracts all
   1,130 NTSC binary assets from the ROM already open in memory.
4. Browse banks as chapters, expand a bank into its authored `;;; $address:
   description ;;;` sections, search bank names, labels, addresses, or literal
   source text—including instructions, values, and comments—and click a label operand
   to follow it to its definition. Only the active bank chapter is expanded by
   default, and selecting it again collapses it. Back/forward navigation
   preserves the trail. At a definition, the compact **References** bar reports
   every resolved use; **Prev**, **Next**, and **Show all** navigate callers and
   data references without losing the active symbol. Definition names and source
   gutters are clickable, so reference browsing also works from search/address
   landings instead of only from an operand. Literal-text results land on the
   exact matching column, and ASM back/forward history retains that caret target.
5. Open **Library** for a practical learning path covering source/data syntax,
   registers and banks, width state, `.B`/`.W`/`.L`, addressing modes, flags and
   loops, calls and stack discipline, and common SNES runtime patterns.
6. Click any 65C816 mnemonic in Source to open its full Library page. A sized
   spelling such as `LDA.W` retains its suffix context, and each instruction page
   shows common forms, a concise example, editing cautions, and direct usages in
   the downloaded Super Metroid source that navigate back into the relevant bank.
   The Library index automatically reveals the opened instruction.
7. Click a source `incbin` path, or switch to **Assets**, to inspect the derived
   file, its PC/SNES provenance, and a read-only hex view.
8. Search a full SNES address (`$8F:805A`, `$8F805A`, `8F805A`, or
   `SNES:8F805A`) or an unheadered PC offset (`0x07805A`, `PC:07805A`, or
   `07805A`) to find exact source anchors, a containing extracted asset, and the
   nearest authored source context. Full 24-bit address operands in source are
   clickable. The source gutter displays exact addresses independently of line
   numbers.
9. Open **ROM** and choose **Build SMEDIT Result** for a non-writing snapshot of
   the real export transaction. **Loaded ROM** shows the bytes from the opened
   file, **SMEDIT Result** shows the fully validated bytes SMEDIT would export,
   and **Diff** groups only changed bytes with their write owner, mutation kind,
   label, PC/SNES range, and a link back into ASM context. Refresh the snapshot
   after making more edits. Loading a different ROM invalidates the old snapshot
   automatically.
10. When a source anchor or extracted asset has an exact semantic owner, use the
    compact **Open in SMEDIT** actions to return to the existing visual editor.
    The reverse bridge covers room headers and complete runtime-state resources,
    door definitions, library-background programs and payloads, tileset graphics,
    metatiles, palettes and CRE, enemy/boss and Samus graphics, pause-map tilemaps
    and graphics, editable text entries, and music song sets. Intentional aliases
    produce explicit destinations instead of silently choosing one owner.
11. Use the persistent workspace control at the bottom of the sidebar and choose
    **Enable Project ASM** to create an isolated source workspace beside the
    `.smedit` file. Its collapsed summary always reports whether Project ASM is
    disabled, enabled, or needs repair; expand it for provenance and asset-sync
    details. Enabling Project ASM automatically selects the **ASM source** build and
    opens **Project (editable)**. Switch between **Original (read-only)** and
    **Project (editable)** at any time; the original view also provides an explicit
    shortcut back to editable source. Project source offers syntax-aware
    editing, a compact `Save *` dirty indicator, `Cmd/Ctrl+S`, buffer
    Undo/Redo, and confirmed recovery actions under the source settings cog.
    **Discard unsaved changes** returns to the last save, while **Restore
    original bank ASM** removes the project override and returns to the pinned
    source snapshot. Enter inherits the
    current instruction indentation and opens an instruction column after a
    label. **Tab** advances a caret to the next four-column stop or indents the
    selected rows; **Shift+Tab** removes up to one indentation level. Paste and
    multi-line replacement are deliberately never reformatted.
    Saving persists both the source override and the `.smedit` project without
    waiting for a full index rebuild. The bottom status bar shows **Indexing ASM…**
    and then **Checking ASM…** while background work refreshes navigation and
    checks saved files for unsafe data literals. Editing remains available;
    newer typing stays unsaved, and obsolete jobs cannot replace a newer index
    or build report. Asar `!defines` such as `!SPF` are colored and
    navigate to their declarations like ordinary source symbols. Resolvable
    references retain syntax colors without persistent underlines; hovering any
    navigable token changes the text cursor to a hand. Use **Cmd+D** on macOS or
    **Ctrl+D** on Windows and Linux to open a definition. Cmd/Ctrl-click and
    middle-click remain convenient pointer equivalents, while ordinary click
    only moves the caret or changes the selection and never recenters the source.
    **Go to Definition** is also in the editor's right-click menu. Literal
    `incsrc` filenames (quoted or unquoted) open the included source in the current
    Project/Reference workspace, relative to the containing file. Nested paths,
    `..` segments within the source tree and unsaved include operands are supported;
    missing files and expression-based paths are never guessed by basename.
    Read-only source includes are clickable too. **Find Usages**
    uses **Cmd/Ctrl+U** and immediately expands the in-place result list; labels
    and macros share the same usage count, Prev/Next cycle, and navigation history.
    Editable-source pointer navigation retains its measured text layout across
    same-page history moves. If a genuinely new page is still being measured,
    the first definition gesture is replayed as soon as that layout is ready
    instead of being silently discarded.
    Completion opens automatically after two typed characters or explicitly with
    **Cmd/Ctrl+Tab** or **Show Completions** in that menu. **Cmd/Ctrl+B** invokes
    Build, and **Cmd/Ctrl+R** saves the active ASM source/project as needed and
    opens or resumes the emulator through the normal validated ROM build path.
    **Cmd/Ctrl+Left** and **Cmd/Ctrl+Right** move through ASM navigation history.
    Standard **Cmd/Ctrl+X**, **C**, **V**, and **A** text editing remains native.
    Size-qualified mnemonics preserve and complete `.B`, `.W`, and `.L`
    rather than collapsing back to a bare opcode. It offers 65C816 instructions,
    Asar directives, `!defines`, global labels, only the `.localLabels` valid in
    the current scope, and indexed `%macros` with their parameter signatures and
    source locations. Inside a call, a compact caret-adjacent signature tracks
    the active argument while respecting nested expressions and quoted commas.
    Accepting a macro completion inserts its parentheses and leaves the caret
    inside. Macro calls use the same definition navigation as labels.
    Disabled modules are excluded from active completion so the editor does not
    suggest source that the current build cannot see. Use
    Up/Down and Enter/Tab to accept or Escape to dismiss it. The completion
    engine reuses the saved project index rather than reparsing a large bank on
    every keystroke.
    With literal source text in the Find field, **Replace in project…** opens an
    explicit project-wide preview. It reports every occurrence and affected file,
    shows before/after rows, and offers a case-sensitive option. Applying the
    preview only stages unsaved bank buffers; it does not touch the source tree,
    project file, or ROM until the normal Save/Build boundary. Replacement text
    is literal, so Asar punctuation such as `!`, `$`, and `\\` is never treated
    as a regular-expression or replacement escape.
    An **EDITED** badge in the bank tree means the source intentionally differs
    from the immutable vanilla snapshot; only `Save *` or an explicit
    **unsaved** count means a buffer or project still needs to be written.
    Use **+ Module** above the banks for new standalone project code. Modules are
    immediately portable authored files. The source-header settings cog changes
    the module name and description; the description is a source-controlled,
    assembler-safe comment inside the `.asm` file. Destructive module deletion
    lives inside that settings dialog and still requires a separate confirmation,
    while the sidebar owns the single ON/OFF toggle and explicit include-order
    arrows. Their
    empty template is safe by default: adding emitted code still requires an
    intentional `org`/free-space target and a hook from existing engine code.
12. **ASM source** is now the active ROM build base. A project with no source
    edits may choose **Loaded ROM** to use the established patch-only path. The
    first new source edit selects ASM source again; Loaded ROM is unavailable
    while any saved or unsaved source edit exists, and export independently rejects
    stale projects that would otherwise ignore a saved override. In source mode
    SMEDIT compiles both the
    immutable snapshot and project tree in isolated temporary directories,
    measures the authored delta, registers it as `asm-source:project`, and then
    plans the normal semantic-edit transaction. Fixed-range editor writes that
    fit wholly inside one known `incbin` are staged into temporary project
    `data/` overrides and assembled into the source base. They appear in ROM
    ownership as `asm-asset:<path>`. Inline ASM data and ambiguous aliases remain
    in the normal post-compile transaction. Existing-room edits now have an
    additional atomic generated-ASM backend: the proven room exporter first
    plans the complete final graph, including variable-sized allocations and
    every pointer update, and SMEDIT assembles that exact graph before export.
    Project-created rooms retain the ROM backend until they have stable source
    identities independent from their preview and allocated room IDs. SMEDIT
    requires the staged and unstaged routes to produce the same final semantic
    ROM hash before export succeeds. Patches, conflict checks, validation, and
    atomic output remain shared with Loaded ROM mode.
    The ROM preview uses the same selection and performs the same build without
    writing an output file.

The application-level **Back** and **Forward** buttons beside **EMU** preserve
the workspace and selected resource across these jumps. For example, opening a
room from bank `$8F`, pressing Back, and then Forward returns to the exact ASM
bank/line and the same room respectively. Ordinary tab, room, tileset, sprite,
sound-track, map-area, and text-entry context joins the same bounded history.
ASM's own compact history remains available for fine-grained
source/asset/Library browsing.

When **Project** source is selected, the source pane is immediately editable;
there is no second per-file edit mode. Its compact gray source header combines
Back/Forward, file or module identity, **Save**, and the source or module
settings cog; it does not spend another row listing keyboard shortcuts. Build
is project-wide, so its single **Build** button lives in the application toolbar
beside **Run** and **EMU** whenever Project ASM is enabled.
Right-click a bank, bank section, search result, or non-module source entry and
choose **Open in Split Right** to inspect both locations side by side. Each
column retains its bank/section, caret, scroll position, and navigation history,
while both views edit one shared document buffer. Opening the same bank in both
columns is therefore safe and changes appear in each view. The editor menu also
provides **Split View** and **Close View**; source headers expose a close-view
button while split. Project modules remain single-view inputs. **Cmd/Ctrl+Z**
and **Cmd/Ctrl+Shift+Z** provide bounded, delta-backed Undo/Redo without retaining
whole copies of large banks.
The current address, PC offset, line count, and compact build state live in the
application's existing bottom-right status bar. **Restore original** provides
the recovery boundary for a modified pinned source file. Large
banks are presented in bounded line pages so syntax highlighting, selection,
and scrolling remain reliable; one continuous right scrollbar addresses the
whole bank, the bottom scrollbar exposes long lines, and edits from every page
are merged into the same whole-file project buffer. The paging implementation
is intentionally hidden from the editing workflow; the toolbar does not expose
internal page ranges. Search, symbol, and diagnostic
navigation highlights the complete destination row with surrounding context;
the highlight then follows the editing caret. **Save** is deliberately a fast
persistence boundary: it atomically writes the source override and saves a detached
snapshot of the `.smedit` project. Disk writes run off the UI thread. Indexing and
saved-file literal validation run afterward, coalescing multi-bank saves into one
index rebuild. Source Save does not regenerate optional graphics PNGs or refresh
the entire bundled-patch catalog; explicit Project Save retains graphics exports
and pattern-library saving. It does not wait for Asar. The button becomes `Build*` whenever unsaved or
newly saved source has not produced a successful build; an explicit **Build**,
**Run**, preview, or export performs the complete validated build. **EMU** only
opens or closes the emulator window; Run builds if needed and starts play. This keeps
ordinary source-saving responsive without allowing execution to use stale
bytes. Unsafe source fails before Asar is launched. Asar still
assembles its include tree as one program rather than incrementally compiling an
individual bank, but SMEDIT reuses an unchanged compiled base and unchanged
generated inputs. Successful builds update only that compact global status.
Source-validation and Asar diagnostics use one collapsible strip
below the source editor, where it cannot obscure a linked line; located problems
open the exact source file with several lines of surrounding context rather than
adding a permanent Problems tool window. Every located error row is also shaded
red with an edge marker directly in source; warning rows use the corresponding
amber treatment, while the ordinary caret/navigation row remains blue. Failed
builds expand the drawer automatically. Located error rows and the failed-build
heading link to source (the heading opens the first located error); the disclosure
arrow still collapses the drawer. Explicit file/line locations from room-data
validation are retained alongside Asar locations. The top Build button uses a
subtle error tint while the current build report is failed. Successful builds leave
a compact status, and the drawer's top divider can be dragged to resize a scrollable
multi-problem list; the chosen height is retained in global SMEDIT layout settings.
Build diagnostics do not open the unrelated ASM workspace/download controls in
the sidebar.

Clicking Build from another editor page in ASM mode opens ASM when the build
fails, revealing the first located error and the diagnostics drawer. General
patch/planning failures still open the drawer even when they have no source line.
This transition is recorded in global navigation history so Back returns to the
previous editor page; a successful Build leaves the current page selected.

Editable banks use a bounded 250-line text window over the whole saved buffer;
the continuous scrollbar hides that implementation detail. Tail targets retain
preceding context even when the file ends just after a window boundary. Wheel
and scrollbar navigation preserve the absolute viewport across overlapping windows,
with scroll restoration deferred until the new text is measured (not a competing
caret-recentering coroutine). The line-number gutter scales with the font and digit
count and never wraps. `AsmEditorViewportTest` exercises the rendered editor with
a synthetic 15,754-line bank, including reopening, large fonts, focused boundary
scrolling and end-to-end scrollbar dragging;
it needs no ROM and cannot modify a real project. Caret movement does
not reparse the complete bank, and an open Source search is refreshed when its
query or saved source index changes rather than rescanning every bank on each
keystroke.

The **Library** and **Original (read-only)** source views are read-only. To turn a Library
example into a real edit, switch to **Source**, select **Project (editable)**, open or search
for the referenced bank/label, click into the source text, and save with
`Cmd/Ctrl+S` or **Save**. Enabling Project ASM creates the editable tree and selects
it as the build base. Loaded ROM remains available only while that tree has no
source edits; beginning an edit returns the project to ASM source automatically.

Source and hex text are selectable and copyable without giving up clickable
labels, mnemonics, or assets. The source tree, asset tree, source canvas, and hex
canvas expose draggable scrollbars for fast navigation. Mouse wheels and
two-axis trackpads scroll normally; **Shift + mouse wheel** scrolls editable
source horizontally, and holding the middle mouse button and dragging pans both
axes in read-only canvases. Mouse back/forward buttons, `Alt+Left` / `Alt+Right`, and
`Cmd/Ctrl+[` / `Cmd/Ctrl+]` follow navigation history, including transitions
between source and Library pages. **Cmd/Ctrl+F** in a source pane opens its compact
**Find in this file** control; the magnifying glass in that pane's header does the
same. Search covers the whole document (including unsaved edits, comments, and
off-screen pages), highlights literal matches, and shows a match count.
**Enter / Shift+Enter** or the arrow buttons cycle matches with wraparound;
**Aa** toggles case sensitivity and **Escape** closes Find and returns focus to
the text. Each split pane keeps its own find session. Original source and Room
Data support the same control. **Cmd/Ctrl+Shift+F** from a source pane focuses the
broader sidebar search; **Cmd/Ctrl+F** while the sidebar is focused stays there.
Sidebar search includes room names/IDs as well as sources, labels, and addresses.
**Cmd+Left/Right** on macOS (**Ctrl+Left/Right** on Windows/Linux) follows ASM
Back/Forward history throughout the sidebar and canvas, including while Find,
sidebar search, or a Library/header control has focus. Shift-modified arrows
remain native text-selection commands. History handling sits above individual
text fields so adding a focused control cannot silently disable navigation.

The Library is bundled and remains available before the external disassembly is
downloaded. Its ten SMEDIT-authored lessons are intentionally a field guide,
not a general computer-science course. Their selectable, syntax-highlighted
examples progress from literal formats, strings, tables, and bit masks through
width state, pointers, comparisons, calls, and SNES runtime patterns; mnemonics
inside the examples open their instruction pages. A selectable Quick-reference
page adds native tables for equivalent decimal/Asar-hex/Asar-binary values,
data widths, NVMXDIZC flags, matching call/return forms, and Super Metroid's
source-verified native/emulation interrupt vectors. A capstone walkthrough traces
Samus's jump from `Make_Samus_Jump` to its paired fixed-point physics tables,
makes a reversible project-owned source edit, and defines the exact compilation
and ROM-diff checks that will eventually prove it safe. The complete 92-mnemonic index
is validated against undisbeliever's **65816 Opcodes** reference (CC BY-SA 4.0),
the official Asar manual, and Western Design Center processor documentation.
Attribution and source links are visible both at the bottom of Library pages and
under **Settings → Credits**.

**Sync assets** re-extracts data from the currently loaded ROM without a network
request. **Redownload** transactionally refreshes the pinned source and derives
the assets again. These controls live in a compact, expandable status bar at the
bottom of the sidebar so the bank chapters remain the primary navigation. A ROM
fingerprint status makes stale assets visible after the loaded ROM changes.

## Source, ROM, and cache boundary

The immutable source contract is shared with the parity harness:

- Repository: `https://github.com/InsaneFirebat/sm_disassembly.git`
- Commit: `11c906f547edc1b57f5a5923cf977fe7b50a3694`
- NTSC asset ranges: 1,130

The managed workspace lives at `~/.smedit/asm/sm_disassembly/`. It contains the
downloaded source plus `data/` files derived from the active ROM. SMEDIT does not
copy the complete ROM into this directory, store its path in the reference, add
the reference to a `.smedit` project, or commit downloaded source/assets to this
repository. Metadata retains only the ROM display name, normalized size, and
SHA-256 fingerprint.

An opted-in project's authored files live in `asm/` at the project root; generated
files live in `smedit/asm/`. See [project layout and manual reorganization](../project/project_format.md#project-root-and-opening-workflow).
Within the generated cache, `original/` is an immutable, offline snapshot of
the pinned source and ROM-derived assets at opt-in time; `workspace/` is the
editable tree that future compilation will consume. Both generated trees are
ignored by the cache's own `.gitignore`; intentionally saved source changes are
mirrored under `asm/overrides/src/`, which is small, reviewable, and suitable for
source control. A recreated local tree reapplies those overrides automatically.
Standalone project-authored routines and tables live under `asm/modules/`, beside an
explicit `.order` file and, when needed, a small `.disabled` manifest. Names and
descriptions are portable metadata comments in the module itself. Modules appear
first in the Project Source tree and can be created, configured, disabled,
re-enabled, deleted, and reordered without editing the pinned `main.asm`.
New workspaces begin with the documented Objectives pause-screen module present
but disabled. It is a readable, buildable reference and contributes no bytes
until the user explicitly enables it.
At build time SMEDIT creates a reserved gateway that includes those modules after
the vanilla source and before generated patch backends. Disabled modules remain
editable and retain their position, but are omitted from the gateway and source
validation until re-enabled. Both `asm/modules/` and
`asm/overrides/src/` are portable authored inputs; their generated mirrors under
`smedit/asm/workspace/src/` are ignored and never treated as source authority.
The ignored `smedit/asm/build/` directory retains the latest Asar log and WLA symbol map so
compiled addresses and diagnostics remain available to the ASM workspace. These
are reproducible local products, never project-authored source.
The `.smedit` JSON records that ASM mode is enabled, its source revision, and the
ROM build-base choice. Existing non-ASM projects default to **Loaded ROM**; enabling
Project ASM selects **ASM source**. A clean workspace may switch back, but saved
source overrides make Loaded ROM invalid until they are restored.
Initial creation is staged and validated before activation. Repairing an
incomplete sidecar preserves the prior tree as a recoverable hidden backup rather
than deleting it.

Installation uses a staging directory and activates it only after the archive,
entry point, asset ranges, and counts validate. Refreshing assets uses the same
staged replacement. Archive paths, expanded size, entry count, and extraction
destinations are bounded before writes occur.

No Git or Python installation is required to download/browse source or extract
assets. A checked-in, data-only range manifest lets Kotlin perform extraction
directly. Source compilation requires exact Asar 1.81. SMEDIT release packages
carry a native build for their own OS/architecture; on first use it is copied
from the read-only application resources into
`~/.smedit/asm/toolchains/asar/1.81/<platform>/`, given executable permissions
where required, checked against the platform package's SHA-256 manifest, and
version-verified before use. Users therefore do not need
Git, CMake, or a C++ compiler. `SMEDIT_ASAR`/`smedit.asar` remains the highest
priority developer override, and source checkout/build into
`~/.smedit/asm/asar/` remains a final development fallback.

Each release runner builds Asar from the pinned revision used by SMEDIT, bundles
the GPLv3 license and distribution notice, then runs
`./gradlew :desktopApp:asarToolchainSmokeTest`. That ROM-free test resolves Asar
through the packaged-resource path, installs it into an isolated home directory,
verifies the 1.81 banner and native executable permission, assembles a synthetic
LoROM, checks its emitted bytes, and confirms WLA symbol output. It runs on the
macOS, Windows, and Linux GitHub Actions jobs before application packaging.
That manifest is regenerated from the pinned upstream `tools/rip_assets.py` with:

```bash
python3 tools/generate_asm_asset_manifest.py \
  parity/work/sm_disassembly \
  desktopApp/src/jvmMain/resources/asm-reference/asset-manifest.tsv
```

The manifest records paths and ROM ranges only; it contains no extracted bytes.

## Accuracy boundary

The global workspace is an annotated **vanilla-source reference**, not a general
binary decompiler. For a normal layout-preserving hack, the data files reflect
the bytes at the loaded ROM's known vanilla ranges and are useful for comparison.
If a hack relocates a resource or adds new custom assembly, the vanilla source
cannot automatically describe that new code and a fixed source range may no
longer own the resource suggested by its filename. The browser therefore stays
read-only and identifies its pinned revision and asset ROM at all times.

The same distinction applies to editor changes that have not yet become a ROM:
**Sync assets** derives from the current in-memory parser image, but it does not
turn project deltas into new symbolic source.

The Address Atlas follows the same accuracy rule. `org` directives, authored
section headings, and recorded six-digit disassembly comments are exact anchors.
A label inherits an address only when it is directly attached to one of those
anchors. SMEDIT does not guess instruction sizes through macros or assembler
conditionals. When an address falls after an anchor but has no exact record, the
UI says **Near** and shows the byte delta; that is navigation context, not a
claim that the selected source line assembled at that address.

The editor provides semantic bridges into the Atlas rather than making users
retype addresses. Room headers, door lists, room-state records, layouts,
backgrounds, FX, PLM/object sets, enemy sets and graphics, special X-Ray data,
room ASM, and scroll data expose compact **ASM** links wherever the ROM stores a
valid address. The tile inspector links a selected door to its `$83`
DoorDef. For an item/object, **ASM** instead targets its selected room/state's
placement: the exact `%PLMPopEntry` line in editable Bank `$8F` source when
verified. The adjacent dropdown offers **Behavior** for the shared `$84` PLM
header (setup/instructions), independently of the placement. Expanded enemy stats link
to the `$A0` species header, and the selected enemy/boss in the sprite catalog
offers the same source jump. A link may land on exact source, the extracted
asset that owns the byte, or clearly labeled nearby source context.
Project-created data without a pinned vanilla anchor remains honest about that
boundary and is not assigned a fictional vanilla address.

The tileset toolbar exposes its detected area metatiles, area graphics,
palette, CRE metatiles, and CRE graphics as one compact **ASM** menu. The sound
editor links the selected track's effective song-set table entry and transfer
data, including when a compatible ROM has relocated the table. Those links use
the same addresses the active decoders consume; they do not assume vanilla
locations when the parser has already discovered a valid relocation.

The reverse bridge applies that rule in the other direction. It builds one
semantic index for the loaded ROM from the room catalog, decoded room states,
door lists, library-background command streams, placed-object definitions,
pause-map tables, decoded text entries, discovered tileset catalog, enemy
species headers, and discovered music pointers. Source lines only offer an
editor destination for an **exact** address anchor; nearby context never
inherits a potentially false visual-editor link. Composite boss, Samus, and
shared pause-map asset names are used only where the engine has no single
semantic pointer for the complete visual source.
Multiple reverse links are collapsed by default (**Used by · N rooms** for room
consumers), with a bounded scrollable list when expanded. They describe the
loaded-ROM index, not the current project's unbuilt changes. A single destination
remains a compact direct link. Read-only source gutters grow with font size so
five-digit line numbers and SNES addresses do not wrap.

The ROM comparison has a different boundary from the extracted reference assets.
It does not infer changes from project JSON. It invokes the same transactional
export builder used by **Export ROM**, including preflight validation, patch
preconditions, write-conflict detection, generated hooks, allocation/relocation,
post-build verification, and write ownership. The resulting arrays remain in
memory; building or refreshing the preview writes neither a ROM nor the project
file. An explicit refresh keeps the snapshot model honest when the user continues
editing.

In **ASM source** mode, source compilation is not an untracked pre-step. The
immutable and edited trees are assembled separately; only their byte delta is
claimed as project source. Asar's generated SNES checksum/complement bytes are
excluded from source ownership because later deterministic writers may replace
them. Any patch, community sprite injection, or semantic exporter that tries to
write a different value over an authored source byte fails through the same
`RomWritePlan` conflict used by loaded-ROM builds.

Emulator launch and restart run this build on a worker dispatcher so Compose can
continue painting and accepting window events. While it runs, the toolbar,
bottom status bar, and emulator viewport show the current compiler/export phase;
duplicate Play/Restart requests are disabled. This removes the operating-system
wait cursor but does not weaken the clean-build contract. The current safe path
may still perform an immutable-baseline compile, a project compile for ownership
and planning, and a final project compile when generated source assets or patch
modules are present. The final pass reuses the immutable ROM compiled by the
first pass instead of assembling that same baseline twice.

Compiled bases also use bounded, session-local content-addressed caches. Their
keys contain the SMEDIT compiler-contract version, loaded-ROM identity, exact
Asar binary and version, immutable or compiled reference input, editable
`src/`/`data/` trees, and every generated source-asset, room, and patch claim. An exact
project/source match can reuse the already prepared base and avoid both Asar and
the preliminary materialization-planning pass. A change handled only by the ROM
patch backend misses that whole-project shortcut but still reuses both compiled
bases, so Asar runs zero times and only planning plus post-compile export remain.
A saved ASM edit invalidates the project base, while a GUI edit mapped into an
`incbin` asset or a source-backed patch invalidates the generated-input base.
Cache entries are copied before use and are never accepted as the final ROM:
every Play/Restart still runs the current export transaction, conflict checks,
and final-ROM/parity validation. The caches are deliberately memory-only for
now, so restarting SMEDIT establishes a fresh trust boundary without adding
generated build artifacts to the project.

Editor-owned source assets follow an equally strict boundary. SMEDIT first runs
the complete validated export plan against the compiled source, then maps only
fixed writes wholly contained by one manifest asset into an in-memory override
of that project `data/` file. Allocator-owned ranges, the community-Samus
injection, inline source data, crossings, and intentional aliases are never
guessed. The working sidecar is not rewritten: overrides exist only in the clean
temporary build tree. Generated ranges are excluded from authored-source
ownership, reintroduced as `ASM_ASSET`/`asm-asset:<path>` claims, and the final
ROM must match the pre-materialization plan except for the derived checksum
bytes described below. Unsupported domains
therefore retain the proven post-compile behavior instead of blocking source
mode or being silently reclassified. Existing-room graphs graduate atomically
rather than write-by-write: generated source owns all planned room bytes or
none. The initial implementation is deliberately byte-faithful to the existing
exporter so it can prove identical semantic ROM output before individual
resources become typed label-driven tables. Asar's four derived internal
checksum/complement bytes are the only exclusion from that parity hash; all
semantic bytes remain exact.

The first typed resource is now a PLM list and its room-state references.
Reachable, edited lists in an existing selector graph emit readable `dw`/`db`
records under names such as `SmeditRoom_91F8_State1_Plms`. State pointers use
`dw SmeditRoom_91F8_State1_Plms&$FFFF`, so Asar resolves the relationship.
Shared pointers emit one table; equal contents at distinct pointers remain
distinct resources. Complete table/pointer ranges are claimed, including bytes
unchanged by the edit, and conflicting source/patch ownership blocks promotion.
The emitter verifies the zero terminator, field widths, and bank-$8F placement,
and asserts the compiled list size. Explicit data primitives avoid inheriting
an edited upstream macro's layout.

The existing room allocator still selects placements, and the original exporter
still provides the exact-output oracle. Other room resources, including rewritten
selector graphs, retain the atomic byte backend. Project-created rooms and
allocations beyond the compiled source ROM retain the ROM backend. This pilot
therefore establishes symbolic PLM references without claiming that Asar now
chooses all room allocations. The GUI model remains authoritative.

After compilation, `smedit/asm/build/generated-rooms.asm` retains the
generated input for inspection beside `symbols.sym`. It describes that compilation,
not unsaved/current GUI edits; rebuild to refresh it. It is a disposable build
artifact, not an editable project module. Cached prepared builds restore their
matching source and symbol report.

The project source sidebar lists **Custom ASM** (your modules, with **+ Module**),
then **Room Data**, followed by **Banks** and **Build & symbols**. Room Data uses
the same theme-colored section heading and retains its expand/collapse control.

In **ASM → Source → Project (editable) → Room Data**, a collapsible tree groups
read-only listings by the same room names used in the room list. Each room exposes
its annotated header, runtime-state fields and objects (PLMs), including object
names, tile coordinates, parameters, shared-list notes and terminators. These
listings are decoded from the **last successfully validated ROM build**, not
editable assembler inputs. They are published automatically to
`smedit/asm/build/room-data.asm` on Build/Run/export in ASM mode, even without GUI
room edits. Project-created-room support remains deferred; a successful build of
a project with added rooms clears this catalog rather than presenting old data
as that build's room list. A failed build retains the prior successful snapshot.
The room inventory comes from the loaded project's input catalog, while all
displayed values are decoded from the build. This retains stable room names and
IDs even when generated state redirects would hide a room from heuristic ROM
discovery (notably Landing Site). Build again to refresh older incomplete snapshots.
The hierarchy uses distinct indentation for Room Data, rooms, and state/object
sections; typing **Landing Site** or **91F8** in the sidebar narrows to that room.

**Build writes**, collapsed separately beneath Room Data, retains the actual
`generated-rooms.asm` input from the last successful source compilation. Typed PLM
sections group under their room names; unmapped raw writes appear under **Other
build writes** with SNES addresses, not meaningless numbered "Room bytes" entries.
No room ownership is inferred from proximity or a shared byte value. Source-state
numbers in generated PLM labels remain original source identities; runtime-state
order in the decoded catalog reflects the built ROM instead.

Both views have syntax colors, selectable multi-line text with real newline
characters for copy/paste, scrollbars, symbolic navigation and Back/Forward.
**Edit room** returns to the owning GUI room. Search includes room names, section
titles and generated labels. Generated text never enters editable buffers, source
replacement or modules. Expanding/collapsing the room tree does not modify source.

The compact **Format checks** action explains the supported six-byte PLM format
and validation limits. Structural/ownership failures still appear in the build
panel, including failures after Asar succeeds: planning/export failures retain
their actual reason instead of only a generic build-failed message. Existing
Asar errors keep their clickable source locations. No claim is made that
arbitrary engine-layout edits can be detected.
The **Build writes** snapshot belongs to a successful *source compilation*, not
necessarily a successful final export, and is not live GUI data. It remains
inspectable after source edits or a later failed compilation. A successful
compilation with no generated input clears it, including a preliminary pass or
ROM-backend fallback. Such a preliminary compilation does not clear the separate
last-successful-build **Room Data** catalog. `$00`/`$FF` in raw writes are data,
not error indicators; their meaning depends on the specific resource.

After GUI room data has been compiled, manifest preflight uses the retained
**pre-materialization source-built ROM**. Revalidating original state identities
against an already-rewritten selector graph falsely rejected condition edits and
reordered states. The initial planning pass, genuine source-identity checks,
ownership conflicts, exact final-ROM parity and postflight structural checks
remain in force; this is not a blanket validation bypass. Prepared cache copies
retain their own immutable validation input. Room-specific preflight errors also
include the room name/address for context.

The real-ROM tests create disposable projects from the explicit fixture contract;
they never read or modify a user's Sandbox. They cover all-state list growth,
selected-state growth and shrinkage with copy-on-write, empty lists, symbolic
addresses, combined room/graphics/patch output, and cache reuse. Synthetic tests
cover ownership conflicts, copier headers, bank boundaries, unsupported allocation
space, new-room fallback, and missing terminators. The packaged-Asar smoke test
also moves a synthetic table and verifies that both state pointers follow its
label; it needs no Nintendo data and runs in the existing cross-platform CI job.

Run the strict real-ROM check with the pinned checkout provisioned:

```sh
export SMEDIT_TEST_ROM='/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew parityBootstrap
./gradlew :desktopApp:asmBuildIntegrationTest
```

`SMEDIT_DISASSEMBLY_DIR` optionally overrides the pinned checkout location. Missing
fixtures fail this strict task, while the ordinary JVM suite reports them as skipped.
The task stages the same pinned Asar executable as the desktop package (requiring
the normal CMake/native build toolchain on a fresh checkout).
Future placement changes that intentionally differ from the legacy allocator must
retain byte-exact tests where applicable and add decoded-resource, pointer/sharing,
and relocation-diff allowlist proofs.

### Extending engine data formats

Adding a field is different from changing its value or adding another record.
The original PLM population is `word ID, byte X, byte Y, word parameter`, followed
by a word-zero terminator. Bank `$82:EB74` reads the population pointer from state
offset `$14`; `$82:EB85` advances it by six bytes. Changing a macro to emit a seventh
byte can assemble successfully while making the engine interpret the next entry
at the wrong offset. Changing the 26-byte state record has the same issue across
its many consumers. Labels fix addresses, not record layouts or runtime contracts.

For small extensions, prefer an existing custom PLM parameter or a separate,
explicitly keyed metadata table when the design permits it. A true format change
requires coordinated engine readers, encoded data, GUI parsers/writers, validators,
and any affected import/export/patch paths. A future supported variant should name
its format/version and provide those codecs and compatibility checks. Unsupported
formats should remain source-owned rather than being rewritten using vanilla GUI
assumptions. Arbitrary ASM can disguise a layout change as plausible vanilla bytes;
current ownership/parity checks are not a general detector or runtime proof of such
changes. Automatic format detection/adapters are not implemented by this pilot.

### Authority and patch backends

SMEDIT does not try to reverse-edit arbitrary handwritten assembly. Each value
has one authority for a given build:

- Saved files under project `overrides/src/` are user-authored ASM.
- Saved files under project `modules/` are user-authored standalone ASM, compiled
  in the explicit order stored beside them when enabled. Disabled module source
  is preserved without contributing bytes or blocking the build. Module bytes receive an
  `asm-module:<name>` owner when Asar's source map identifies their range.
- Structured room, graphics, music, map, and patch settings in the `.smedit`
  model are GUI-authored data.
- Source generated from GUI data is a temporary, reserved build product. It is
  deliberately absent from the editable workspace, which effectively freezes
  that generated region against a second source of truth.

If user-authored ASM and a GUI feature target the same byte, the ownership plan
reports a conflict instead of choosing one silently. For reciprocal structures
such as room data, the safe future path is a shared typed codec—decode source or
ROM into the semantic model, edit that model, then encode a generated asset—not
text replacement inside a bank file.

Both build modes ultimately produce the same kind of output: a binary SNES ROM.
The difference is where an edit enters the pipeline:

```text
Loaded ROM mode
  loaded ROM
    → SMEDIT semantic edits and ROM-byte/IPS patches
    → validation
    → final ROM

ASM source mode
  ASM source + extracted data assets
    → generated assets and supported ASM patch modules
    → Asar compilation into a ROM
    → remaining post-compile ROM-byte/IPS patches
    → validation and parity proof
    → final ROM
```

In other words, ASM is an earlier build stage rather than a different final
format. ASM mode does not require every existing patch to acquire a source
implementation before it can be used.

Terminology matters at this boundary:

- An extracted **`.bin` source asset** is input to the disassembly, normally
  consumed by an `incbin` directive. Graphics and compressed room data are
  common examples.
- A **ROM-byte patch** is a set of writes to offsets in an already compiled ROM.
  IPS records and SMEDIT's fixed hex writes are examples.
- A generated **ASM overlay/module** is source input compiled by Asar before the
  remaining ROM-byte patches run.

All three contain or produce bytes, but they belong to different stages. The
documentation avoids calling ROM-byte patches “`.bin` patches” so `.bin` remains
unambiguous shorthand for disassembly source assets.

A patch can advertise a ROM implementation, an ASM implementation, or both,
but one build selects exactly one implementation. **Loaded ROM** uses direct ROM
writes. **ASM source** prefers an explicitly registered source implementation;
patches that have not migrated yet continue through the post-compile ROM backend.
Migration is atomic per patch family: if any required record cannot be represented
safely, the complete patch stays on the ROM backend for that build.
The two current ASM implementation levels are:

1. **Generated overlay** — validated write intents become a readable temporary
   Asar `org`/`db` module. This establishes source ownership without pretending
   byte patches are already symbolic source.
2. **Curated module** — a future patch-specific implementation can use labels,
   mnemonics, macros, and source-level allocation while keeping the same backend
   and parity contract.

Samus Physics is the first dual-backend patch. Its GUI remains authoritative;
Loaded ROM mode uses the established writer, while ASM mode compiles the
generated overlay and records those bytes as `ASM_PATCH`. The pre-materialized
ROM plan remains the oracle, so the final output must be byte-identical. This
lets patch families migrate incrementally rather than forcing an unsafe all-at-
once conversion.

The real-Asar combined-backend regression also includes an enabled legacy byte
patch and a disabled conflicting byte patch. It compares the enabled legacy and
Samus Physics results with Loaded ROM mode, checks unchanged-build cache reuse,
and verifies that a later invalid patch is still reported as a build failure.

## Architecture

- `AsmReferenceRepository` owns download, archive safety, transactional cache
  activation, ROM normalization, exact range extraction, and metadata.
- `AsmProjectWorkspaceRepository` owns transactional project-sidecar creation,
  immutable original/working-tree separation, bounded atomic source writes,
  trackable source overrides, modified-file detection, offline restore, and
  recoverable repair of incomplete workspaces.
- `AsmProjectCompiler` performs clean out-of-tree reference/project builds,
  preserves optional copier headers, captures Asar diagnostics, requires a valid
  emitted symbol map, applies size-checked staged data overrides, and returns
  separate minimal authored-source, generated-asset, generated-room, and generated-patch ranges
  rather than an opaque ROM replacement.
- `AsmBuildArtifactRepository` retains the latest local Asar log and WLA map;
  `AsmBuildOutputParser` turns compiler messages into file/line diagnostics and
  `AsmWlaSymbolParser` indexes emitted labels and exact source-line addresses.
  Every retained report includes a fingerprint of the source tree that produced
  it, so repaired source can never inherit stale errors after reopening SMEDIT.
- `AsmSourceLinter` catches data literals that exceed their explicit
  `db`/`dw`/`dl`/`dd` storage width before Asar can silently keep only the low
  bits. It also resolves complete `%macro(...)` calls and checks their argument
  counts, including variadic minimums. The editor runs these conservative checks
  after a short typing pause and immediately feeds Problems plus red/yellow source
  rows; incomplete calls are ignored while being typed, and Asar remains the
  authority for the complete language. Intentional truncation remains expressible
  with an explicit mask.
- `AsmSourceAssetMaterializer` projects eligible write-plan bytes into exact
  manifest-owned `data/` files, excludes allocations and ambiguous ownership,
  and leaves the project workspace unchanged.
- `AsmSourceRoomMaterializer` atomically converts the complete validated
  existing-room write graph—including allocator-owned payloads and pointer
  changes—into a temporary ASM input. It cannot partially migrate a graph, and
  project-created rooms deliberately fall back until source IDs are defined.
- `AsmRoomPlmTable` encodes the first typed generated room resource. Its name
  follows a consuming room/state identity rather than its allocation address;
  all consuming state-pointer fields reference that label. Discovery decodes
  reachable lists from the validated plan and checks full-resource ownership.
- `AsmPatchBackendRegistry` explicitly declares which patch families have an
  ASM implementation; unregistered patches retain the ROM backend.
- `AsmSourcePatchMaterializer` converts validated writes for a selected generated
  overlay backend into temporary Asar source and separately owned `ASM_PATCH`
  ranges. Overlapping or out-of-ROM records fall back instead of being guessed.
- `AsmToolchain` pins and verifies Asar 1.81, preferring an explicitly configured
  executable, then the release-bundled native compiler, and only then using or
  provisioning a managed developer compiler.
- `AsmSourceParser` reads `main.asm` include order and descriptions, then indexes
  every source file, authored section, global label, arbitrarily nested dotted
  label, flattened local alias, struct member (including indexed forms such as
  `Enemy[1].properties`), Asar/WLA `Parent_Local` alias, cross-reference, and
  extracted asset. This lets
  a reference such as `DebugConst_DisableAudio` navigate to the `.DisableAudio:`
  declaration beneath `DebugConst:`. Reference resolution uses the same
  local-scope rules as label navigation and ignores comments and quoted strings;
  it is not a raw text search.
- `AsmSourceReferenceAuditor` compares every navigable reference in the pinned
  source with Asar's emitted WLA symbols. `:desktopApp:asmSymbolCoverageTest`
  fails if any emitted symbol used by source cannot reach a SMEDIT definition;
  the strict parity report runs that audit after rebuilding the reference.
- `AsmAddressAtlas` is the shared bidirectional address layer. It converts
  canonical LoROM SNES/PC coordinates and resolves exact source anchors,
  contextual anchors, and extracted asset ownership without decoding assembler
  output heuristically.
- `AsmSemanticBridge` indexes exact addresses consumed by SMEDIT's production
  decoders and maps recognized source/assets back to Rooms, Tiles, Sprites, and
  Sound. The index is built once per loaded ROM so browsing assets does not
  repeatedly scan the room and species catalogs.
- `AsmWorkspaceState` owns reference/project selection, source edit buffers and
  dirty state, guarded multi-bank replacement staging, source/asset/library mode,
  cross-mode history, ROM freshness, preview state, progress, and safe navigation.
- `RomExporter.build()` is the single disk-independent export transaction.
  `RomExporter.export()` only adds the atomic output-file write around that
  validated result, so preview and export cannot drift into separate pipelines.
- `AsmRomPreview` compares canonical unheadered PC bytes, retains the write-plan
  owners and labels for each changed range, handles optional copier headers and
  expanded results, and supplies the three deliberately named read-only views.
- `AsmLibrary` owns the original guided lessons, complete mnemonic catalog,
  practical forms/examples, categories, and stable page identifiers.
- `AsmWorkspaceSidebar` and `AsmWorkspaceCanvas` provide the bank tree, search,
  syntax-colored source, navigable Library, real-source instruction examples,
  and binary inspector. Typography is taken from SMEDIT's global UI setting.
  Shared selectable, two-axis text panes keep source and hex navigation behavior
  consistent.

The install contract is covered by normal synthetic archive/range tests. The
real pinned download plus all 1,130 byte-exact extractions can be exercised with:

```bash
export SMEDIT_TEST_ROM='/path/to/clean/unheadered/Super Metroid.sfc'
./gradlew :desktopApp:asmReferenceIntegrationTest
```

## Reciprocal source/GUI ownership — first authoring slice

The generated room backend above proved compilation/export parity; it was **not**
yet reciprocal source editing. The target is one authoritative representation per
resource, with GUI and source/raw views acting on the same document. Source-owned
tables belong in authored ASM; graphical/level assets can instead own project
data files referenced by ASM. Neither requires a second editable `db` dump of the
built ROM. Room Data remains a last-successful-build inspection report.

In ASM source mode, supported PLM lists now resolve their authority automatically
against the immutable source/ROM baseline. There is no per-room **Link** step:

| Authored ASM list | Room-editor changes | Authority / behavior |
|---|---|---|
| Unchanged | None | Normal room editing remains available; opening the field dialog does not take ownership. |
| Unchanged | Objects, scroll commands, or state graph | Room editor. Existing additions, removals and state differences remain authoritative and are included by the established build backend. |
| Changed, supported fixed layout | None | ASM. The room preview follows source automatically, including after save/reopen. |
| Changed | Overlapping GUI resource/graph changes | Explicit conflict. Neither version is discarded; build stops until ownership is resolved. |

Comparison uses decoded values, not whitespace, comments or literal spelling.
Sharing is checked across every consumer, not only the currently selected room.
Automatic ownership is derived from the documents rather than saved as another
toggle. Source Undo back to the original values releases that automatic ownership.
Build-time checks run independently of the GUI, so visiting Room Info first is
not required for either protection or correct export.

**Room Info → State Data → Placed Objects → Edit objects…** opens compact field
controls for an unchanged or source-owned supported list. The dialog names the
rooms/states sharing the list and the current fixed-size boundary. Changing
a literal X/Y or parameter updates the actual `%PLMPopEntry` token in project
`bank_8F.asm`, retaining surrounding labels, comments, other literal spellings and
line endings. The **ASM** action opens that same editable declaration, not the
built-ROM report. Editing a supported source field updates the room's PLM preview
without building. GUI and source edits share the source document and undo/redo
history. Normal project Save (including Cmd/Ctrl+S outside ASM) saves pending source
buffers too; it does not trigger a build.

Until first opened as placement source, GUI-managed rooms show **Objects · Room
editor**. Their original Bank `$8F` list is a baseline, not a second edited version.
For example, Landing Site may have a rewritten state graph, common item additions
and state-specific exceptions—not merely a longer vanilla list. The migration
below preserves those differences instead of flattening them into one shared table.

### Selected-object navigation

Right-click an item/PLM's tile on the room canvas, find **Items / PLMs** in the
tile properties panel, then click **ASM** next to its name. In source
mode, navigation verifies the selected room/state's list identity and matches
the full ID/X/Y/parameter tuple against the **current buffer**, including unsaved
changes. It highlights the matching entry rather than stopping at the population
label or opening the shared behavior definition. Identical duplicate records,
invalid drafts, ownership conflicts and unmatched states are not guessed.

Original list identity follows the room state's symbolic `%PLMPop` reference,
verified against the base ROM's complete list. This recovers the missile-refill
and Big Pink lists despite their duplicated `$8F:8550` population annotations in
the pinned reference. Address-only lookup retains all candidates and requires a
unique full-list ROM match; conflicting state references remain unavailable.

For a supported GUI-managed room, this action now automatically migrates its
complete effective object lists into project source, then opens the selected entry.
This changes source/ownership **in memory**, without compiling, exporting or
touching the base ROM. Project Save persists both the source and state identities.

If safe migration is unavailable, ASM opens a compact **Placement** inspection: room,
selected condition, PLM type, tile X/Y and parameter, plus the reason source
navigation is unavailable. This is explicitly a read-only snapshot of the room
preview at selection, not the last build and not another editable ASM dump.
The actual migration refusal is shown; no guessed conversion occurs.
**Behavior** still opens the shared implementation. Back/Forward preserves the
captured placement context; snapshots are discarded when switching projects.
Navigation never uses the source lens's last-valid cached preview as if it were
current authored text.

### Fixed-layout authoring boundary

Earlier explicit links remain supported through the project's
`asmWorkspace.plmSourceBindings` identities (label, original pointer, record count),
**not another list of object values or PLM deltas**. No new binding is needed for
automatic source recognition or field edits.
The immutable original source supplies the initial identity/layout checks; the
project's authored source owns the values. The existing ROM-only workflow is
unchanged for unlinked resources. Linked resources require ASM mode.

This intentionally bounded pilot supports the standard six-byte records expressed
as single-line `%PLMPopEntry(ID, X, Y, Param)` calls and a zero-word terminator:

- Decimal, `$` hexadecimal and `%` binary literals are editable through the GUI.
  Known pinned source labels are decoded, but the GUI never silently replaces a
  symbolic field with a literal. Edit that expression in ASM explicitly first.
- Record count and source/state-pointer placement stay fixed. Add/remove,
  relocation, per-state detachment and arbitrary expressions are next steps, not
  implemented reciprocal capabilities. Existing unlinked room editing still
  supports its usual additions/removals through the established build backend.
- Invalid/incomplete source or a changed PLM macro keeps the session's last valid
  preview, marks it out of date and blocks visual writes to that resource. A stale
  object dialog cannot overwrite a newer source revision. On reopen there may be
  no last-valid session preview; the source problem is still explicit.
- For older explicit links, **Use room editor** requires original object values
  first. Automatic source ownership instead releases on restoring the source
  values. Neither action hides changed source behind a base-ROM preview.
- Saving checks both workspace and authored override text against the buffer's
  saved baseline. External changes are rejected, not overwritten. Automatic
  external-file merging/reloading is not implemented.

Builds verify the decoded model against actual Asar bytes **and** its emitted
label address, confirm every existing state reference, then claim the complete
table and state-pointer fields as source-owned, including unchanged bytes. Thus a
patch cannot overwrite an unchanged byte in a linked list, and the GUI exporter
cannot replay an independent PLM delta on top. Compiled source cache reuse retains
these ownership claims. The project compiler does not guess custom engine layouts.

Validation: `AsmPlmDocumentTest` covers lossless field edits and rejected formats;
`AsmPlmSourceBridgeTest` covers reciprocal buffers, shared undo, invalid/stale
drafts, ownership, persistence and external-edit rejection, plus exact placement
navigation, GUI-owned fallbacks, state mismatch and inspection history. The strict
`asmBuildIntegrationTest` includes a disposable real-ROM Landing Site test proving
GUI → source → GUI → Asar output **without linking**, retained sharing, undo/reopen
and patch-conflict rejection. Another fixture combines a rewritten Landing Site
with common/per-state item edits and an independently source-edited Wasteland,
then verifies that overlapping source/GUI edits fail without dropping either.
Tests never edit the user's Sandbox or other live project.

### Fast room projections and startup

The desktop opens a small room-source workspace (`main.asm`, `macros.asm`, and
`bank_8F.asm`) before the full IDE index. Room objects can render and be edited
while the remaining banks, references, and diagnostics initialize in the background.
The bottom status bar reports **Loading room objects…**, **Indexing ASM…**, and
**Checking ASM…** without adding another toolbar. A failed index retains the usable
room workspace and offers **Index unavailable · Retry** in that same bar. A late
global-reference load never locks project editing or replaces the project view.

`smedit/asm/projections/` contains disposable decoded views and immutable baseline
symbol lookups. Room snapshots include source/dependency SHA-256 keys, a codec version,
payload checksums, ROM identity, and stable room/state identities. Symbol lookups
are keyed to the complete immutable source snapshot. Cached scalar
tokens are checked against the current source. Missing, truncated, incompatible,
or checksum-invalid snapshots are rebuilt; cache write failures do not prevent
valid previews. Only a few generations per cache family are retained. No preview
cache is an editable authority or a ROM-build input. Builds retain their independent
source decoding, allocation, Asar output, and final-ROM parity checks.

The live object model is keyed to its relevant source, macro definitions, and state
membership rather than the global editor/index revision. Editing another bank or
changing unrelated room content therefore does not reparse the PLM view. ASM edits
invalidate the affected model; GUI edits still change those same source values.
Invalid drafts retain the session's last valid preview, mark it **out of date**, and
block visual mutations. With no valid source preview, the canvas does not silently
substitute vanilla ROM items.

Before source is indexed, generated bank mirrors are reconciled with their authored
overrides or immutable original snapshot. Differing old mirror contents are preserved
under `smedit/asm/recovery/`; authored files are never overwritten by this recovery.
Per-project synchronization serializes short mirror/save transactions, not the full
index rebuild. External helper consumers are read from authored sources and unsaved
buffers, including disabled modules, even while the IDE index is incomplete.

Future resource codecs can reuse `AsmVerifiedCache` with their own format version
and complete dependency keys. Their cached models must remain disposable, must be
validated, and must not bypass the resource's normal source/build ownership checks.

### Relocatable room-object source

`AsmRoomObjectSource` is the next, bounded step beyond fixed original lists. On
first **item → ASM** navigation for a supported GUI-owned room, the bridge:

1. Resolves its stable state manifest and computes complete effective PLM lists
   from the loaded ROM, common edits and state exceptions. Existing independent
   source edits, changed record layouts and unsupported resource changes refuse
   conversion without deleting either version.
2. Appends authored `SmeditObjects_<room>_…` macros to editable `bank_8F.asm`.
   State wrappers reference List macros; shared lists retain sharing. Every list
   contains direct `%PLMPopEntry` records, including multi-state rooms. There are
   no generated per-object Common helpers; state-local exceptions stay local.
   Original vanilla declarations and surrounding source are unchanged.
3. Verifies that decoding those macros reproduces every effective list, then
   retires only the room/state `plmChanges` streams. Schema 11 stores
   `RoomEdits.asmPlmSourceVersion = 1`, plus existing state identities—not a second
   authoritative copy of the object values. Historical operations and unrelated
   tile/enemy/FX edits remain intact.

Both ASM edits and ordinary GUI object add/remove now change those macros. A
state-local edit first forks a shared List, while **All states** writes the actual
PLM row into each current list (once per physical list, not once per consumer).
Duplicated rows retain the same pickup flag ID: they represent one collectible
across room states, not multiple pickups.
Added conditions clone their template's list, inheriting common additions/removals
**and template exceptions**.
Editing a row directly in ASM or the scalar dialog changes that list and any
states sharing the whole list. Equal rows in different lists are independent;
editing one does not silently rewrite the others. Use **All states** in the room
view to deliberately apply a change to every list. The scope indicator describes
current membership: an identical object present once in every state shows All states,
not a hidden per-object link.

The inspector's **Add new PLMs to** preference defaults to **All states** and applies
to items, stations, gates, door caps and scroll triggers/extensions in both backends.
It is not an existing object's scope indicator. Each object's membership menu shows
its actual coverage and offers **Edit this state**, **Edit all states**, **Apply to all
states**, and explicit removal scopes. Normal edits preserve existing membership;
an intentional local edit detaches a shared list. A broad edit refuses distinct
objects/command streams or incompatible gate/door-cap collision geometry in another
state. Save rows keep one shared spawn; gate body/shot-block rows move together in
GUI transactions. Raw ASM edits still affect the list/resource explicitly edited,
not every independently duplicated row.

In single-state rooms, All states and This state have the same membership. Adding
a condition shares its template's existing List; a subsequent state-local placement
forks the List. This direct-row form also matches the pinned vanilla bank `$8F`
PLM declarations (`%PLMPopEntry`, followed by `dw $0000`). Identical bytes in independent lists
are **not** evidence of intentional sharing and are never automatically merged.

Migration, GUI placement/scalar edits, and source/project Save tidy the affected
room's owned helper graph in the same source undo step. Legacy Common calls expand
to direct rows in every active list, including shared and independent multi-state
lists. Unreferenced Common/List definitions and wrappers for deleted states are
removed without changing entry order, literal spelling, values, or live List
identities. Surviving list names are not renumbered. Handwritten comments are retained;
generated item-name comments and the old helper-based header are refreshed. Calls
elsewhere in the bank or other indexed project sources, including unsaved/disabled
custom modules, protect legacy definitions even though managed lists now contain
direct rows. Unknown helper bodies are conservatively retained. This is
a bounded source transformation, not an arbitrary Asar dead-code optimizer.
The decoded resource graph must match before/after cleanup or the edit is refused.

Malformed authored records block GUI placement/removal without queuing an edit or
overwriting the draft. The status bar reports **Object edit blocked** in error
color for 12 seconds; malformed PLM rows include `bank_8F.asm:<line>`. Correct the
source and retry the placement. A successful edit replaces the stale error status.

Older saved rooms receive this cleanup on their next GUI object edit or Save, even
if the bank was already saved and no GUI placement changed. Build/Run preparation
also saves managed source first. Merely opening or navigating does not rewrite
authored text. Invalid drafts remain saveable and are not cleaned until repaired;
Build still validates the complete tree. Cleanup is source-undoable, and newer
typing during Save remains unsaved. Redo, save/reopen, and item → ASM navigation use
the resulting document. Synthetic
tests cover repeated add/remove, single-to-multi-state transitions, state exceptions,
external consumers, comments/CRLF, and exact undo. The strict Asar integration test
also builds Bubble Mountain before and after adding a condition and compares every
compiled state list with the source model.

Authored PLM entries carry short `; PLM: <name>` comments, using
the editor's item/door/scroll names (including project custom items), plus a field
legend and list terminator comment. Unknown IDs remain identified by hex, not a
guessed item name. GUI object edits refresh these names in the same undo step;
handwritten comments, numeric spelling and original bank declarations are retained.
Additional notes can follow a second semicolon. Comments never determine object
values. Existing authored rooms acquire comments on their next GUI object edit or Save;
merely opening a source file or typing ASM does not automatically rewrite it.

At build time the shared room planner receives the decoded source lists. It
allocates variable-sized, whole resources in bank `$8F` and rewires every state
pointer. The generated placement plan calls the **authored macro**, not a copied
editable dump. Asar must emit exactly the planned bytes, table sizes and pointers;
whole-resource claims and final-ROM parity still protect against overlapping
patches. A missing source document, unknown format version or leftover independent
PLM deltas fails closed. ROM-only/headless builds cannot silently ignore the marker.
Allocation addresses remain build results, not persisted source identities.

Migration is one source-undo step that restores both text and the prior GUI PLM
streams. It refuses undo across changed state membership. GUI object Undo/Redo
uses source history and rejects stale revisions. A newly added state's declaration
cannot be undone while that state still references it. Source Save also persists
the project's room-state/ownership metadata. Ordinary Project Save includes all
dirty source banks and checks already-saved managed room blocks for cleanup.

Current boundaries: this conversion supports existing rooms in projects without
added rooms. Changed resource links, conflicting
original-source edits and arbitrary macro expressions still need review. Compound
tile/PLM operations and whole-room reset now use the transactions described below.
Original fixed-layout declarations retain their earlier count/address restriction;
the appended managed lists support growth/shrinkage and state-local detachment.
This is **object-source migration, not full room-header/selector/level-data source
authoring**. Room Data remains a read-only build report.

Validation adds synthetic `AsmRoomObjectSourceTest` coverage and migration/undo/
save/reopen bridge tests. The strict real-ROM integration compares migrated Landing
Site states with the established ROM exporter, then exercises GUI edits, shared
undo, a new condition, source-list growth, real Asar output and cache reuse. No test
reads or modifies a user's live Sandbox project.

Next: other source-aware compound edits, initial room scroll tables, a first asset-owned
graphics example, and stable source identities for project-created rooms; then
extend the same authority contract to the rest of the room graph.

### Reciprocal scroll-trigger commands

Source-owned room objects now support the referenced command resource of a `$B703`
scroll trigger. In a source-owned room, select its tile → **Edit commands** to use
the screen grid; **ASM commands** opens the exact command declaration. **ASM** on
the object still opens its placement row. Creating a scroll behavior writes the
placement and commands in one shared source/undo transaction.

```asm
macro SmeditObjects_ACB3_Scroll_1()
    db $00, $01 ; Screen (1, 1) → Blue (normal)
    db $03, $02 ; Screen (2, 2) → Green (open + lower view)
    db $80 ; End of scroll commands
endmacro

; Inside each affected state's object-list macro:
    %PLMPopEntry($B703, $04, $05, SmeditObjects_ACB3_Scroll_1&$FFFF)
```

Screen IDs in code are zero-based row-major indices; the GUI and generated comments
use one-based screen coordinates. Each command is a byte screen index plus a byte
scroll value (`0` red, `1` blue, `2` green); `$80` terminates the stream. This is
**runtime trigger behavior**, not the room's initial scroll table or a simulation
of the running camera. The grid uses the editor's configured font sizes and can
scroll horizontally instead of shrinking cells in wide rooms.

Command values are authoritative in the authored Bank `$8F` source. A named
reference has no physical pointer until Build: its numeric preview parameter is
an internal placeholder, not an address. The typed preview cache retains the
resource name and verifies its body and room bounds against current source.
Build allocates each referenced identity once in bank `$8F`, emits its label and
macro invocation, then assembles the referencing PLM lists. Exact command bytes,
terminators, list bytes, state pointers, complete allocation claims, and other
write owners are checked against the established planner. No command values are
duplicated in project JSON or reapplied as a second post-compile room patch.

GUI edits target the selected trigger in its chosen scope. New placement defaults
to **All states**; existing triggers retain their membership unless their scope
menu requests otherwise. **This state** copies shared lists/commands on write;
**All states** updates the affected trigger rows together with their command reference.
Another trigger is not changed implicitly. Editing a
shared command macro directly in ASM updates all its references. Independent
equal streams are not deduplicated by their contents. Unused supported owned
macros are pruned; helpers mentioned by custom source (including disabled modules)
remain protected. Source Save refreshes screen/value annotations and preserves
handwritten notes. Stale open GUI drafts refuse to overwrite newer source.

Existing custom room/state command maps migrate into named resources with their
sharing preserved; source undo restores their legacy metadata. Original numeric
ROM command pointers remain intact until a GUI edit creates a private named
resource. Verified `RoomPLM_*` declarations are read from current project source,
so supported literal ASM edits are visible immediately. Unknown hack pointers use
the loaded ROM; a changed or unsupported known declaration does **not** silently
fall back to old ROM bytes. Matching an address alone does not establish source
ownership: a hack's differing ROM commands are retained when the source baseline
is untouched, while changes on both sides require review. Build verifies original
fixed pointers against actual compiler labels and command bytes; a mismatch
blocks the build with guidance to import a named resource. Original declaration
resizing is not a new general room-source layout guarantee; use the relocatable
named form for GUI growth.

The bounded codec rejects missing/misplaced terminators, non-byte expressions,
invalid values, out-of-room screens, duplicate screen writes that the grid would
otherwise discard, cross-room resource references, and ambiguous placements.
An empty named stream is a valid no-op and remains visible as an empty-behavior
diagnostic. Invalid drafts remain saveable, but source-owned edits/builds fail
closed. The synthetic tests cover source/GUI round trips, shared-state isolation,
undo, save/reopen, cache validation, growth/removal, header handling and conflicts;
the strict real-Asar integration checks migration, growth, empty streams, actual
pointers and preservation of a ROM-only patch in a disposable project.

### Reciprocal save-station spawns

Source-owned existing rooms now support save-station PLMs **and their paired
load-station records**. In the tile inspector use **Add Station / Gate → Save
Station**, then **Apply Spawn**, **Reset/Auto**, or **Spawn ASM** on the station.
The ordinary **ASM** link still opens its placement. The spawn link adopts an
unchanged native record on demand, then opens the authoritative authored fields.
No extra toolbar or second editable generated-data page is introduced.

```asm
macro SmeditObjects_91F8_Save_0_2()
    dw $91F8 ; Room · bank $8F
    dw $8916 ; Incoming door · bank $83 (example: must actually enter this room)
    dw $0000 ; Door BTS
    dw $0000 ; Camera X
    dw $0000 ; Camera Y
    dw $0068 ; Samus Y · screen top
    dw $FFE0 ; Samus X · screen center (-32)
endmacro

; In a state's PLM list: this parameter is a slot, NOT the record's address.
    %PLMPopEntry($B76F, $08, $08, $8002) ; Save slot 2 in the room's area
```

The name encodes owning room, area, and slot. The seven words are one fourteen-byte
bank-`$80` record; authored values live beside the PLM macros in the same Bank
`$8F` source buffer, while the checked build placement invokes the macro at the
actual load-table slot. Its original Bank `$80` declaration is baseline data,
not a competing editable authority after adoption. Editing that baseline record
too produces an ownership conflict before the authored record can overwrite it.

The spawn is **shared across room states** because the engine indexes by area
and slot, not by state. State-local placement still detaches the PLM list. Removing
one state's station keeps its spawn while another state references it; removing
the last reference through the GUI writes an explicit seven-zero-word released
record. That declaration intentionally survives list cleanup, so reopening or
rebuilding cannot resurrect the original slot. Raw ASM can retain an independent
nonzero checkpoint record after removing its PLM; that is an explicit source
choice, not an automatic deletion. Released source slots remain claimed by their
room and may be reused there, not silently transferred to another room.

Ordinary source saves are limited to areas `0..5` and indices `0..7`. Allocation
reserves occupied native records, other rooms' authored records, and legacy GUI
overrides. A matching room pointer is insufficient to claim the gunship: an occupied
native record must also have a verified ordinary save PLM for that slot. Elevator,
Ceres, debug, and other engine-owned destinations are not spare save capacity.
The incoming door must enter the owning room; cross-area save migration is still
a separate compound operation. Duplicate owners, a PLM pointing at a released or
missing spawn, wrong rooms/doors, altered record widths and out-of-word values
fail closed. Invalid drafts remain saveable and a last-good preview is not an
editable current model.

Placement plus spawn authoring is one source/undo transaction. **Apply Spawn** also
updates camera and Samus fields in one undo step. Negative offsets use ordinary
sixteen-bit two's-complement values; out-of-range GUI input is rejected rather
than wrapped. Unchanged scalar spelling, CRLF, symbolic fields and handwritten
notes survive GUI edits. Changing a symbolic field visually requires first making
that expression a supported literal in ASM. Native door BTS is preserved when
creating/adopting a record, and remains directly editable in authored source.
Legacy GUI spawn overrides migrate into source with metadata restored by undo;
there is no duplicate JSON spawn value for an adopted slot.

Typed caches verify current save declarations, native ownership, area/state links
and the base ROM. Builds claim all fourteen record bytes (including unchanged
bytes), assemble the actual macro, check its byte count and exact planner output,
and preserve the post-compile ROM-patch path. The regression tests cover headers,
negative offsets, notes/symbols, slot protections, shared-state lifetime, stale and
invalid previews, undo/reopen and mixed ASM/ROM-patch output. Header coverage also
fixed a pre-existing parser bug: the load-station pointer-table read must include
the copier-header offset, just like its target-record reads.

#### Batch acceptance checks

1. In an already source-owned room, add a save station through the tile inspector.
   Select it, open **Spawn ASM**, change a Samus offset and verify the inspector.
2. Change both camera and spawn fields with **Apply Spawn**; one Undo restores both.
   Save/reopen and confirm the same values return without a full IDE index wait.
3. Duplicate the room state, remove the station from one state, then from its last
   state. Verify the first removal keeps the shared record and the last releases it;
   Undo restores the station and record together.
4. Build/Run with an unrelated existing ROM patch enabled. In the emulator, test
   saving and resuming from the station on a suitable floor (the PLM does not build
   room geometry for you).
5. Batch-check energy/missile refill stations, map stations, gate color/direction,
   door-cap variants and scroll extensions. These use the same direct PLM-row lens;
   only save and scroll triggers require the new linked-resource codecs.
6. Leave **Add new PLMs to → All states** selected and verify new placements appear
   in every branch. Use an object's membership menu to **Edit this state**, recolor
   a gate/door cap, and verify the sibling is unchanged. Undo restores the complete
   operation. Distinct sibling variants should block **Apply to all states**.

## Reciprocal initial screen scrolls

**Room Info → Screen Scrolls → Initial grid → ASM** opens the selected state's
actual authored table. Changing a grid cell automatically adopts the room in ASM
mode; there is no Link step and no dependency on adopting PLMs. ROM-patching mode
continues to use its existing state-scoped scroll edits.

Initial values live only in `bank_8F.asm`:

```asm
macro SmeditScrolls_91F8_Table_1()
    db $02 ; Screen (1,1) · Green
    db $01 ; Screen (2,1) · Blue
    ; ... exactly width × height bytes; no terminator
endmacro
macro SmeditScrolls_91F8_State_73746174652d31()
    %SmeditScrolls_91F8_Table_1()
endmacro
```

`asmInitialScrollSourceVersion = 1` is an ownership marker, not a stored second
grid. Adoption imports current GUI common/state overrides and retires those deltas
with metadata-aware Undo/Redo. Sharing follows original pointer identity **and**
effective values; independent equal-valued tables never become aliases. GUI edits
fork a shared table for the selected state. Raw edits of a shared table affect all
its wrappers. Added states clone their template; reordering uses stable IDs.
Private scalar edits preserve whitespace, CRLF and handwritten notes. Generated
coordinate/color comments refresh on GUI edits/Save; unreferenced owned tables and
deleted state wrappers are pruned, except those referenced elsewhere in project ASM.

The small typed lens is independent of the full IDE index and caches the macro
envelopes for the exact buffer revision. It accepts `db` color literals and verified
baseline symbols, validates every active wrapper and exact dimensions (at most
50 screens), and never guesses arbitrary expressions. Invalid drafts stay saveable;
the last-good grid is shown with an error and cannot overwrite that draft via GUI.
Build decodes current saved source, never a preview/cache, then allocates each named
table once in bank `$8F`. Asar invokes the authored macro and writes symbolic state
pointers at `+$0E`; full allocation/pointer claims and exact compiled-byte parity
protect the result. Original ASM pointer/table/header changes which conflict with
this adopted resource fail before replacement. The source-built native scroll loader
at `$82:EB20-$EB6B` must also match the loaded baseline; a custom byte layout/preset
needs a matching codec rather than silently reusing this lens. Ordinary ROM patches retain their
normal post-compile ownership checks. `$0000/$0001` presets are never treated as
addresses; adopted grids always receive concrete tables.

Engine correction: `$82:EB3D-$EB67` generates Green on upper rows and Blue on the
bottom row for `$0000`; only a one-row room is uniformly Blue. `$0001` remains all
Green. This preview correction applies to both build modes. Initial tables still
precede PLM setup, incoming Door ASM, room setup ASM and gameplay writers, so changing
one does not remove a trigger/door override. Trigger command macros are a distinct
format with an `$80` terminator.

Acceptance: use Landing Site, change a cell, open **Initial grid → ASM**, change
its `db` byte and return to the room. Switch states to check isolation, Undo/Redo,
save/reopen, then Build/Run with an unrelated patch enabled. Native multi-row preset
rooms should preview Green above/Blue bottom before editing. Synthetic tests cover
round trips, aliasing, migration, malformed drafts, notes, headered allocations and
ownership guards; `asmBuildIntegrationTest --tests '*initial screen scrolls*'`
checks real Asar output, coexistence with PLM authoring, sentinel materialization,
new-state graph relocation, ordinary patches and base-ROM immutability.

Current boundaries: project-created rooms are guarded rather than partially
translated. Room generation, resizing and reset now have joint
source/layout transactions (see below). Explicit re-sharing controls remain
future work.

## Reciprocal room FX

**Room Info → Default FX → ASM** opens the selected state's authored header in
`bank_83.asm`. Each existing door-specific row also has an **ASM** link. A visual
FX edit automatically adopts the room's supported tables in ASM mode, independently
of PLMs or initial scrolls. ROM-patching mode retains its normal FX overrides.

The authored table contains readable, commented `dw`/`db` fields: four words
(incoming door, liquid start, target, signed 8.8 speed), then eight bytes
(delay, type, A/B/C options, palette effects, animated tiles, palette blend).
State wrappers name the table; JSON records only `asmFxSourceVersion = 1`, not
a second copy of its values. Adoption imports existing common/state/door overrides
and retires them with metadata-aware Undo/Redo.

Generated type fields include the native effect name, for example
`db $06 ; FX Type - Water`. GUI edits and Save refresh those generated names while
preserving scalar values, CRLF and semicolon-separated handwritten notes. The
dropdown, decoded header and writer share `NativeFxTypes`; old SMILE labels such
as `$18 = Lightning` are not used as native handler names. `$0E-$1E` are explicitly
Unused/no-op-handler slots in this dispatch. Invalid drafts still save unchanged.

Ordered incoming-door headers remain intact. The engine chooses the first match
or the complete Default header (`door = $0000`); that header ends lookup itself.
A table without Default ends with the **two-byte** `dw $FFFF` stop, which also
represents an empty/no-FX table. Editing Default in such a table adds a complete
header without removing its door branches. FX type is an **even byte offset** into
the native dispatch table (`$00-$2C`), not a sequential enum. Palette blend is also
a byte offset, not an arbitrary palette ID.

GUI edits detach a shared table for the selected state. Raw edits affect every
state wrapper referencing that table. Room-wide FX operations update each distinct
resource once; new conditions inherit their template and later detach on editing.
Independent equal-valued tables never become aliases. Scalar edits preserve manual
notes and whitespace. Save/GUI cleanup removes unreachable owned declarations while
keeping references from custom ASM. The lightweight startup index includes both
banks `$8F` and `$83`, so FX adoption does not wait for the full IDE index.

The typed lens validates every active wrapper, field width, record layout, selector
order, unique selectors, incoming-door destination, native type offset, stop and
bank boundary. Invalid drafts remain saveable; the last-good preview is marked with
the problem, and GUI writes cannot overwrite it. Format failures retain an authored
file/line in the build drawer; an unsafe FX type links directly to its field.
The map previews **Default FX**,
not a simulated incoming-door choice or later runtime mutation. Door-specific
values remain editable in their separate rows.

Build decodes saved source, allocates whole tables in bank `$83`, invokes the
authored macros at checked placements, and emits symbolic state pointers at `+$06`.
Complete resource/pointer claims and final Asar-byte parity compose with PLM,
initial-scroll and save-spawn authoring, state-graph relocation and ordinary ROM
patches. Conflicting edits to original state pointers/FX bytes fail explicitly.
The initial source-built native loader at `$89:AB82-$89:ACC2` and dispatch pointers
at `$83:AC18-$83:AC45` must match the loaded baseline; custom layouts/dispatches need
a matching codec. These checks are bounded ABI checks, not proof of arbitrary hack
runtime behavior.

**Indexed FX is a separate boundary.** Mother Brain's table at `$83:A0A4` has
two additional headers *after* Default, selected by her AI through `Load_FX_Entry`
(`$89:AB02`). Moving only the door-visible prefix would break her acid transitions.
Adoption therefore blocks that native table without replacing source or metadata.
Supporting it needs explicit indexed records/consumers, not a relaxed door parser.
Project-created rooms remain guarded. Whole-room reset and biome themes have joint
source transactions (below). Arbitrary expressions are not interpreted
by this first FX lens.

Acceptance: in an ASM project use **Room Info → Default FX**, change water/height,
open **ASM**, edit a field, return and switch states. Check Undo/Redo, save/reopen,
and Build/Run with an unrelated patch enabled. Also check a room with incoming-door
FX: editing one branch must preserve Default and its siblings. `AsmFxSourceTest`
covers reciprocal edits, adoption, aliases, invalid drafts, startup-independent
preview, notes, strict reads, state cloning and headered allocation/ownership.
`asmBuildIntegrationTest --tests '*authored FX compose*'` verifies real Asar output
alongside PLMs, initial scrolls, door variants, empty tables, added states and ROM
patches; fixtures are isolated and the base ROM remains immutable.

## Reciprocal enemy populations and GFX

Existing-room actors now use the same source/GUI authority as objects and FX.
In an ASM-source project, **Room Info → Enemy Actors / Enemy Graphics → ASM**, or
an actor's **ASM** shortcut in tile properties, opens that room/state's authored
data rather than the species header. The first GUI add/remove/update also adopts
the effective lists automatically. ROM-only projects keep their established delta
path and do not show these source shortcuts.

One `bank_A1.asm` buffer contains the paired definitions, so population and GFX
changes have one atomic edit/save/undo boundary. The build planner places each
population macro in `$A1` and each graphics macro in `$B4`; their stable identities
do not encode allocated addresses. Per-state wrappers reference each list
independently: a GUI population edit forks only its shared population, and a new
species forks/adds graphics only when necessary. Raw edits to a shared declaration
affect every referring state. New conditions inherit the template's two references.
These paired state wrappers are planner recipes, not contiguous records to invoke
inline from a custom module; placement invokes the two tables in their own banks.
Saving removes unreachable owned helpers but retains definitions referenced by
other project source. Scalar GUI edits retain other literal spellings and notes;
source saves refresh only generated descriptions, leaving executable text intact.

```asm
macro SmeditEnemies_91F8_Population_1()
    ; species, pixel X, pixel Y, init, properties, extra properties, parameter 1, parameter 2
    dw $D13F, $0028, $0038, $0000, $2800, $0000, $0000, $0000
    dw $FFFF ; End of population
    db $00   ; Required kills
endmacro

macro SmeditEnemies_91F8_Graphics_1()
    dw $D13F, $0001 ; species, VRAM destination (palette offset 1 → OBJ palette 9)
    dw $FFFF
endmacro
```

All eight actor words, list ordering, trailing required-kill count, intentional
no-GFX actors and transfer-only GFX entries survive adoption. Zero-transfer headers
do not acquire invented transfers. Equal `GRAPHADR` addresses are not treated as
species aliases: `$A0:8BF3` matches the species ID itself. The native engine has
**32 runtime enemy slots**, regardless of the preview parser's 64-record safety
cap. Graphics validation checks four entries, palette offsets 0–7, raw LoROM source
bounds, nonzero/aligned transfer sizes and the `$7000..97FF` staging footprint;
special size-bit-15 quadrant offsets are retained. Native tables are audited with
the clean ROM fixture. Companion initialization and dynamic AI spawning still need
encounter-specific knowledge; these checks are not a gameplay simulation.

Source-only values are decoded from a revision-local typed lens, including during
progressive startup, without waiting for the full IDE index or invoking Asar.
Invalid drafts remain saveable, preserve the last valid live preview and block GUI
overwrites/builds. JSON schema 13 stores `asmEnemySourceVersion = 1`, not another
population/GFX value list; common/state enemy deltas retire with metadata-aware
source undo. Header/population macro layout changes, native loader changes and
competing original tables/state pointers/selectors fail explicitly instead of
being hidden by generated output. Missing or partial paired placements fail before
a ROM is published. Allocations and every state pointer are checked against actual
Asar output and the same write-owner planner used by normal ROM patches.

Try updating an actor's X or parameter in the map, following **ASM**, editing the
same row back, switching states, then Undo/Redo and Save/reopen. A GFX-less actor
such as an existing elevator should retain its native arrangement. Build/Run with
an unrelated patch enabled. `AsmEnemySourceTest` covers these boundaries, raw
aliases, malformed/missing GFX, fifth transfers, macro layout changes, helper
cleanup, headered claims and the complete native-table audit.
`asmBuildIntegrationTest --tests '*authored enemy populations*'` proves real Asar
composition with PLMs, scrolls, FX, new conditional states and ordinary patches in
isolated projects. Project-created rooms remain guarded pending stable reciprocal
source identities; existing-room reset now spans all authored resource banks.

## Compound tile / object pattern strokes

Functional tile patterns (including floating pattern placement) now work in
existing-room ASM projects. Painting an item, door cap, refill, gate or save
station pattern automatically adopts its object lists when needed. The drag
previews tiles and objects in memory; releasing it validates and publishes all
PLM rows and save-spawn resources as **one** Bank `$8F` source edit. JSON retains
the layout edits, not a parallel stream of PLM values. There is no new toolbar or
special ASM-only placement workflow.

Undo/Redo from either the room editor or ASM reverses both halves together,
including when viewing a different state sharing that layout. The source-history
sidecar captures the layout identity and before/after operations, rather than
depending on whichever room happens to be selected later. Newer layout/state
changes must be undone first; a rejected undo changes neither source nor model.
Saving/reopening and compiling use the ordinary resource-owned layout exporter
and checked authored PLM placements.

Placement keeps the existing This state / All states semantics. Private object
edits detach their PLM list, independently of the tile layout's sharing choice.
Gate shot blocks retain their paired body. A final prospective-geometry check
prevents an All-states gate or door-cap pattern from leaving an incompatible
independent state layout. Save strokes reserve distinct free slots across the
whole drag; repainting retains the station's slot and customized spawn. Slot
exhaustion, invalid source and source changes during a drag never leave a
half-applied pattern. A rejected stamp rolls back that stamp while retaining
earlier valid stamps in the same drag; an abandoned drag discards its preview.
Objects can still be added when their decorative tile bytes already match.

Scroll-trigger patterns remain explicitly guarded: a tile-only pattern does not
carry a named command-list resource. Use **Add scroll trigger** for those. This
slice does not imply that selecting/moving tiles also selects or moves actors or
PLMs. Added-room source identities retain their existing guards; whole-room reset
and themed generation transactions are described below.

`AsmPlmSourceBridgeTest` exercises staging, rollback, scopes, gate pairs, slots,
cross-view undo, interrupted strokes, reopen and the ROM-only backend using
synthetic fixtures. The real-Asar `authored enemy populations` integration also
stamps patterns, undoes/redoes them, and checks the final geometry and PLMs in the
same compiled ROM alongside enemies/GFX, FX, scrolls, new states and ROM patches.

## Compound room generation

In ASM mode, **Generate** and applying a reviewed learned-room candidate now
combine layout changes, initial camera grids and scroll-PLM cleanup in one
source/model transaction. If objects or grids still use GUI deltas, both
adoptions are planned privately first. No source, ownership metadata or tiles
change until the complete plan validates. One Undo/Redo from Rooms or ASM reverses
the whole action, including adoption, legacy command maps and save-spawn overrides.
The same layout sidecar used by pattern strokes owns the geometry history.

Generation follows the existing layout-sharing choice. Choosing **This state**
forks its layout; choosing all sharing states updates those states' initial grids
and removes their old scroll triggers/extensions together. Unrelated layouts and
their camera data are untouched. The generated camera grid is Blue above / Green
on the bottom row, matching the existing generator policy. Target states retain
their shared list/grid identities where possible; a shared subset forks once,
and independent resources are never merged simply because their values match.

States sharing a generated layout may have different object and actor lists.
Generation protects the union of their placements, including state-specific
door caps and important-object approach areas, then writes one common maze.
Each state's non-scroll objects, actors, graphics, FX and scripts remain separate;
generation does not merge those lists. Private geometry overrides that disagree
with the shared layout still require **Make layout unique to this state…**.
Placements changed while the sharing prompt is open require a fresh generation.
Malformed drafts, changed inputs, custom unsupported initial presets,
or competing original PLM source block the entire operation. A later source undo
also refuses to overwrite newer layout/state changes. Existing handwritten notes,
custom references and save resources survive; only unreachable owned scroll
helpers are collected. Source plans check the project source revision so a newer
macro/custom reference cannot be overwritten by an older plan.

Generated rooms persist resource-owned tile edits until layout-source adoption;
adopted layouts instead update their authored `SmeditLayout_*` declarations.
Bank `$8F` PLM/grid values remain authored, not duplicate JSON values. Reopen, raw ASM edits, subsequent
GUI edits, and checked compiled placements use the same existing lenses. ROM-only
generation retains its delta backend. Bulk Generate/Reset skips source-owned
rooms: bulk reset must not remove only geometry while leaving generated camera
source behind. Use the single-room reset or Undo on the complete source transaction
instead. Added-room identities remain separate work; themes now participate in
the generation transaction described below.

`AsmRoomGenerationSourceTest` covers atomic adoption, shared subsets, invalid
drafts, stale plans, save preservation, cross-view undo, reopen, typed export,
ordinary Generate and the ROM-only backend. The real-Asar integration
`compound generated room data` builds the authored result with an ordinary ROM
patch, then undoes adoption and builds the restored legacy inputs as well.
The Flyway shared-maze integration builds generation, undo and redo across its
different native states, checking one layout pointer and unchanged state-specific
object lists, enemy populations/GFX, FX and scripts.

## Compound room resize

**Room Info → Size → Resize** now works with authored initial scroll grids.
Room dimensions apply to every state: all Layer 1/BTS/embedded Layer 2 layouts
and every physical initial grid resize together. Existing sharing and independent
resource identities survive. New geometry is air and new camera cells are Blue;
overlapping cells retain their values, literal/symbol spelling and handwritten notes.
Private layout differences are rebased onto the resized native resource so Save,
reopen and subsequent edits preserve the same result. Shrink followed by growth
does not resurrect cropped tiles.

The transaction adopts existing GUI object/grid values when needed, then remaps
each scroll command by its old screen coordinates. Writes to cropped screens are
removed. Named command sharing survives; original ROM streams become named room
resources instead of being modified where other rooms may still reference them.
Incoming Door ASM is remapped after all GUI DoorDefs are final during export.

Shrinking refuses to strand previously in-room objects, actors, incoming-door
destinations, incoming ASM scroll writes or save cameras outside the room. Mixed
incoming routines that cannot be rewritten safely, invalid source, stale previews,
unsupported dimensions (the existing initial-grid limit is 50 screens), and added
room projects fail before publishing source, dimensions, metadata or geometry.
An unsuccessful Apply leaves the resize controls open and reports the reason.

Source and GUI undo/redo restore dimensions, layouts, grid/command declarations
and any retired GUI inputs together. Newer room/layout edits, actor source changes,
incoming door changes and active strokes prevent stale source undo from overwriting
the current project. The base ROM remains unchanged. This adds no project schema or
second persisted copy of authored camera values.

`AsmRoomResizeSourceTest` covers sharing, independent layouts/commands, growth,
cropping, adoption, invalid/stale inputs, reciprocal undo, save/actor/entrance guards,
reopen and typed export. `asmBuildIntegrationTest --tests '*source room resize*'`
checks real Asar output, an ordinary ROM patch, byte-identical layout/grid previews,
remapped trigger commands and a subsequent build after undo.

## Compound room reset

**Generate → Reset room** now works for existing rooms in ASM-source projects.
It restores the native room header, dimensions, state graph, layouts/BTS/embedded
Layer 2, objects, initial scrolls, FX, enemies/GFX, doors and save-spawn records.
Only this room's edit record is reset. Native state identities remain as lightweight
metadata; source ownership and independent GUI overrides are retired together.
Global palettes, patches, minimaps, other rooms and handwritten original-bank edits
are deliberately not reset.

The reset removes unreachable generated declarations across banks `$8F`, `$83`
and `$A1`. Custom/disabled-module references pin helpers and their transitive
dependencies, even across banks; handwritten notes and unknown custom declarations
remain intact. Malformed macro envelopes fail before any transaction is published.
This is a reset of room-editor-owned data, not a checkout of all ASM affecting a room.

One shared undo boundary spans every participating bank and the complete room
model. Undo/Redo from the canvas or any participating ASM bank restores all of them,
including added states, resource sharing, prior ownership and the previously selected
state. Older room-editor history remains usable after undoing reset. Newer edits in
any participating bank, changed room metadata, external helper references, incoming
doors or unfinished strokes block a stale transition without changing part of it.
Incoming entrances on expanded screens must be moved before resetting dimensions.
Projects containing added rooms and bulk reset remain guarded.

`AsmRoomResetSourceTest` and `AsmProjectWorkspaceTest` cover cleanup, stale inputs,
metadata-only transactions and reciprocal cross-bank history. The isolated real-Asar
test `whole room source reset` builds the edited room, its native reset and its
restored authored result, checking all resource families, save records, state graphs,
an unrelated room/ROM patch and base-ROM immutability.

## Compound biome themes

**Generate → Theme → Generate room** now stages theme and layout changes together
in existing-room ASM projects. One transaction owns generated Layer 1/BTS geometry,
initial grids and obsolete scroll-trigger cleanup in bank `$8F`, Default FX in
bank `$83`, state tileset overrides and any palette recolor. Invalid FX drafts,
unsupported indexed FX, unavailable palettes, malformed inputs or stale previews
leave every part unchanged. Any layout-scope choice happens before publication.

The theme applies to the states sharing the generated layout; choosing a private
layout limits it to that state. FX keeps all incoming-door variants, unrelated
fields, handwritten notes and independent resource identities. Shared target subsets
fork once rather than modifying sibling states. Source values remain authored in
ASM, not duplicated as JSON FX overrides. Tileset selectors and palette overrides
still use the existing project metadata/export backend; this is not yet reciprocal
graphics-asset authoring.

Palette recolors are **global to the selected tileset**, as before. Other rooms
using that tileset see the same recolor. Undo restores the previous palette bytes
and effect tag, not a guessed vanilla palette, and refuses to overwrite a newer
edit to that palette. Unrelated palettes are untouched. Clearing a theme's palette
effect selection does not implicitly delete an existing custom override.

Canvas Undo/Redo or ASM Undo/Redo from a participating bank restores source,
ownership, geometry, tileset and palette together. Newer raw source or room-model
changes block the whole transition; prior GUI history remains usable after undo.
Raw edits through **Room Info → Default FX → ASM** continue to update the GUI.
Save/reopen and Build use the same projections. Added-room projects, bulk
generation of source-owned rooms, and Mother Brain's indexed after-Default FX
remain guarded.

`AsmRoomThemeSourceTest` covers adoption, scope, aliasing, palette guards,
reciprocal raw edits, save/reopen and the actual Generate entry point.
`asmBuildIntegrationTest --tests '*themed generation compiles*'` checks actual
Asar output for theme and layout data, the previous result after undo, redo,
ordinary ROM patches and base-ROM immutability.

## Reciprocal room layouts

**Room Info → Layout → ASM** adopts an existing room's effective layouts and
opens their editable decoded declarations in project `bank_8F.asm`. This is
different from the read-only, last-build **Room Data** catalog. Navigation does
not require a build first. The source pane's **Edit room** link returns to a
state using that exact shared/private layout, including after the canvas reloads.

`SmeditLayout_<room>_Data_<identity>()` macros contain a `dw` L1 byte count,
row-major `dw` L1 tile words, row-major `db` BTS bytes, and any embedded L2/tail
data. Comments show zero-based row/column coordinates; rows are at most sixteen
cells long. The first word is the allocation header, not a tile. State wrappers
reference the same macro when their complete layouts are linked. Hidden allocated
blocks, embedded L2, odd tails and unused bytes survive adoption without
normalization. These are typed source assets: do not invoke an uncompressed
layout macro at a ROM `org`. SMEDIT's build encoder validates the literals and
LZ5-compresses them before Asar compiles the generated placement plan.

Painting, filling, selection-property edits and ordinary tile batches update
those same literals; raw source edits refresh the canvas. Existing handwritten
comments and literal spelling survive unrelated GUI edits. **Make layout unique**
clones a whole source asset; **Use another state's layout** relinks it explicitly.
Equal independent assets are not merged. Invalid drafts retain the last valid
preview (or show an unavailable-source warning on fresh load), remain saveable,
and block GUI writes and builds rather than falling back to native geometry.

Adoption retires legacy tile deltas with one reciprocal undo boundary. New source
edits prevent older GUI undo from overwriting them. Generation, themed generation,
functional tile/PLM patterns, resize and whole-room reset retain their joint
source/model history without reintroducing duplicate tile operations. Source
builds use validated resource inputs and checked allocations/pointers; ROM-only
export cannot ignore authored ownership. Project schema 14 records ownership and
resource identities only. Added-room projects and selection-to-multiple-state
application remain guarded; copy/share complete layouts instead.

Manual round trip:

1. Generate Flyway, then open **Room Info → Layout → ASM**.
2. Find an `L1 tiles` row. Copy a visibly different nearby tile's word into one
   ordinary wall cell to see an appearance change. Changing only `$8xxx` to
   `$0xxx` changes collision to air but keeps the same graphic. Do not change
   the allocation header or door/trigger cells for this test.
3. Use **Edit room** to inspect the change. Paint a block in the canvas, then
   reopen **Layout → ASM** to see its authored value.
4. Check shared/private states and Undo/Redo. Build to refresh **Room Data**.

`AsmRoomLayoutSourceTest` covers adoption/retirement, raw and GUI edits, sharing,
invalid drafts, reopen, typed export, generation/themes, patterns, resize/reset,
retained allocation bytes and reverse navigation. The real-Asar integration
`authored layout code round trips` builds Flyway after raw edits, undo, a private
fork and restored sharing, checking decoded bytes and state-specific resources.

## Next layers

The separation between editable source and executable output is intentional.
Follow-up work can now be incremental:

1. Continue conservative editing assistance beyond the shipped scope-aware
   instruction/directive/label/macro completion and debounced diagnostics: add
   richer expression-aware hints, additional Asar-aware checks, and explicit
   statement formatting that preserves expressions, comments, literal forms,
   and `.B`/`.W`/`.L` suffixes. Literal project-wide
   find/replace already has a reviewed, buffer-only preview, and Tab/Shift-Tab
   provide selection-scoped indentation without a whole-file formatter.
2. Continue the artifact sinks for semantic editors. Existing-room graphs now
   have an atomic byte-faithful generated-ASM backend, including variable-sized
   allocations and pointer changes. Replace that bridge incrementally with
   typed labels/tables. The generated PLM-list/state-pointer pilot and read-only
   result navigation are implemented. The separate reciprocal authoring pilot now
   shares fixed-layout declarations and relocatable authored object macros with the
   GUI, including state exceptions and copy-on-write. Initial screen grids,
   scroll-trigger commands, paired save-spawn records and ordinary door/default FX
   now use the same authority contract, including paired enemy populations/GFX.
   Tile/PLM pattern strokes, room generation, themes, resizing and reset now have atomic source/model undo boundaries.
   Decoded room layouts/BTS/L2 now have a reciprocal typed source asset as well.
   Next add one asset-owned graphics example. Then extend full
   authoring to rewritten state graphs, doors, headers, and state selectors;
   then give project-created rooms stable source
   identities. Graphics, map, text, and sound encoders should follow the same
   project-owned `data/`/source boundary before Asar.
3. Extend the reverse semantic bridge to remaining specialized title/menu,
   cinematic, and generated-runtime assets as those editors gain stable semantic
   selection entry points. The inventory must explicitly include regional
   Japanese content: the Japanese font/tile encoding, Japanese strings and intro
   text/control streams, regional intro/cinematic images and frame tables, plus
   item-drop, weapon, beam, and projectile graphics/animations.
4. Use retained project symbols for emulator run-to-address, breakpoints,
   registers/stack inspection, and source-mapped crash diagnostics.

Arbitrary-hack disassembly and automatic source merge/conflict resolution are not
implied by the current editable workspace.
