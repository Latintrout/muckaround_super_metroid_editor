package com.supermetroid.editor.asm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Disposable, versioned projections. A cache can accelerate reads, never confer source ownership. */
internal class AsmVerifiedCache(private val directory: File) {
    @Serializable private data class Envelope(
        val version: Int,
        val dependency: String,
        val payloadHash: String,
        val payload: String,
    )

    private val json = Json { encodeDefaults = true }

    fun <T> loadOrBuild(
        name: String,
        dependency: String,
        serializer: KSerializer<T>,
        validate: (T) -> Unit = {},
        build: () -> T,
    ): T {
        require(name.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid projection cache name" }
        // Immutable keys prevent an older background job overwriting a newer snapshot.
        val target = File(directory, "$name-${hash(dependency)}.json")
        val cached = runCatching {
            require(!Files.isSymbolicLink(directory.toPath()))
            require(target.length() in 1..MAX_CACHE_BYTES)
            require(target.canonicalFile.parentFile == directory.canonicalFile)
            val envelope = json.decodeFromString(Envelope.serializer(), target.readText())
            require(envelope.version == VERSION && envelope.dependency == dependency)
            require(envelope.payloadHash == hash(envelope.payload))
            json.decodeFromString(serializer, envelope.payload).also(validate)
        }.getOrNull()
        if (cached != null) return cached
        val value = build().also(validate)
        // Read-only/full disks and torn/corrupt caches must not stop an otherwise valid preview.
        runCatching {
            val payload = json.encodeToString(serializer, value)
            val bytes = json.encodeToString(Envelope.serializer(), Envelope(VERSION, dependency, hash(payload), payload)).toByteArray()
            require(bytes.size <= MAX_CACHE_BYTES)
            directory.mkdirs()
            require(!Files.isSymbolicLink(directory.toPath()))
            require(target.canonicalFile.parentFile == directory.canonicalFile)
            val temporary = Files.createTempFile(directory.toPath(), ".projection-", ".tmp")
            try {
                Files.write(temporary, bytes)
                try {
                    Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { Files.deleteIfExists(temporary) }
            // Keep a few reusable generations, not an unbounded history of every source save.
            val family = Regex("${Regex.escape(name)}-[0-9a-f]{64}\\.json")
            directory.listFiles().orEmpty().filter { it.name.matches(family) && it.canonicalFile.parentFile == directory.canonicalFile }
                .sortedWith(compareByDescending<File> { it == target }.thenByDescending(File::lastModified))
                .drop(4).forEach { it.delete() }
        }
        return value
    }

    companion object {
        private const val VERSION = 1
        private const val MAX_CACHE_BYTES = 32 * 1024 * 1024L
        fun hash(text: String): String = hash(text.toByteArray())
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        fun fingerprint(parts: Map<String, String>): String = hash(parts.toSortedMap().entries.joinToString("\n") {
            "${it.key.length}:${it.key}:${hash(it.value)}"
        })
    }
}
