package com.crossberry.noxs.runtime.api

import com.crossberry.noxs.runtime.NoxsPermissionCenter
import com.crossberry.noxs.shared.MiniJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * NoxsApiBridge security tests (Noxs API spec §11-§14, §32-§33): every
 * request is identity-checked, permission-gated and answered with
 * structured errors. Security tests attempt malicious inputs and confirm
 * they are rejected.
 */
class NoxsApiBridgeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private var allGranted: NoxsPermissionCenter? = null

    @Before
    fun setUpCenter() {
        // The temporary root exists only after the rule runs — build the
        // center here, never in a field initializer.
        allGranted = NoxsPermissionCenter(
            stateDir = temporary.newFolder("state-all"),
            androidProbe = { true },
            featureProbe = { true }
        )
    }

    private fun bridge(
        center: NoxsPermissionCenter = allGranted!!,
        ports: Map<String, ApiPort> = emptyMap(),
        known: Set<String> = emptySet()
    ): NoxsApiBridge = NoxsApiBridge(center, ports, known)

    private fun request(
        packageId: String = "tree",
        module: String,
        operation: String,
        arguments: Map<String, Any?> = emptyMap(),
        apiVersion: String = "1",
        requestId: String = "req-1"
    ): String = writeJson(
        mapOf(
            "v" to apiVersion,
            "packageId" to packageId,
            "requestId" to requestId,
            "module" to module,
            "operation" to operation,
            "arguments" to arguments
        )
    )

    /** Minimal deterministic JSON writer for test fixtures. */
    private fun writeJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean -> if (value) "true" else "false"
        is Number -> value.toString()
        is Map<*, *> -> value.entries.joinToString(
            ",", "{", "}"
        ) { (key, item) -> "${writeJson(key.toString())}:${writeJson(item)}" }
        is List<*> -> value.joinToString(",", "[", "]") { writeJson(it) }
        else -> writeJson(value.toString())
    }

    private fun parse(response: String): Map<String, Any?> = MiniJson.parse(response) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun errorOf(response: String): Pair<String, String> {
        val body = parse(response)
        assertFalse(body["ok"] as Boolean)
        val error = body["error"] as Map<String, Any?>
        return (error["code"] as String) to (error["message"] as String)
    }

    // ------------------------------------------------------- happy paths

    @Test fun `allowed call with a wired port answers ok`() {
        val port = ApiPort { _, _, _, _ -> mapOf("id" to "w1") }
        val b = bridge(ports = mapOf("window" to port))
        val response = b.handle("tree", request(module = "window", operation = "open"))
        assertTrue(parse(response)["ok"] as Boolean)
        val data = parse(response)["data"] as Map<String, Any?>
        assertEquals("w1", data["id"])
    }

    @Test fun `permission self-query maps through the catalog`() {
        val b = bridge()
        val response = b.handle(
            "tree", request(module = "permissions", operation = "query")
        )
        assertTrue(parse(response)["ok"] as Boolean)
    }

    // -------------------------------------------------- identity + shape

    @Test fun `identity mismatch between host and body is rejected`() {
        val b = bridge()
        val response = b.handle(
            "tree", // host-verified identity
            request(packageId = "other", module = "window", operation = "open")
        )
        assertEquals(NoxsApi.ERR_IDENTITY_MISMATCH, errorOf(response).first)
    }

    @Test fun `unknown packages are denied`() {
        val b = bridge(known = setOf("tree"))
        val response = b.handle("ghost", request(module = "window", operation = "open"))
        assertEquals(NoxsApi.ERR_PERMISSION_DENIED, errorOf(response).first)
    }

    @Test fun `malformed requests are rejected without details`() {
        val b = bridge()
        val malformed = listOf(
            "",
            "not json at all",
            "[1,2,3]",
            """{"module":"window"}""",
            request(module = "window", operation = "open", apiVersion = "9")
        )
        malformed.forEach { body ->
            val (code, message) = errorOf(b.handle("tree", body))
            assertEquals(NoxsApi.ERR_MALFORMED_REQUEST, code)
            assertFalse(message.contains("/"))
        }
    }

    @Test fun `traversal request ids are rejected`() {
        val b = bridge()
        val (code, _) = errorOf(
            b.handle("tree", request(module = "window", operation = "open", requestId = "../traversal"))
        )
        assertEquals(NoxsApi.ERR_INVALID_ARGUMENT, code)
    }

    // ------------------------------------------------- permission gating

    @Test fun `denied permission returns the stable code`() {
        val center = NoxsPermissionCenter(
            stateDir = temporary.newFolder("state-deny"),
            androidProbe = { true },
            featureProbe = { true }
        )
        center.deny("terminal.execute")
        val b = bridge(center)
        val response = b.handle("tree", request(module = "terminal", operation = "execute"))
        assertEquals(NoxsApi.ERR_PERMISSION_DENIED, errorOf(response).first)
    }

    @Test fun `terminal execution requires explicit permission (spec §13)`() {
        val b = bridge() // no decisions recorded at all
        val execute = b.handle("tree", request(module = "terminal", operation = "execute"))
        assertEquals(NoxsApi.ERR_PERMISSION_DENIED, errorOf(execute).first)
        val write = b.handle("tree", request(module = "terminal", operation = "write"))
        assertEquals(NoxsApi.ERR_PERMISSION_DENIED, errorOf(write).first)
    }

    @Test fun `every privileged module maps to a catalog permission`() {
        val b = bridge()
        val cases = listOf(
            Triple("window", "open", "window.create"),
            Triple("window", "close", "window.control"),
            Triple("web", "open", "web.open"),
            Triple("terminal", "read", "terminal.read"),
            Triple("terminal", "subscribe", "terminal.subscribe"),
            Triple("logs", "read", "logs.read"),
            Triple("package", "info", "package.read"),
            Triple("package", "install", "package.install"),
            Triple("system", "info", "system.info"),
            Triple("storage", "read", "storage.package")
        )
        cases.forEach { (module, operation, expected) ->
            assertEquals(expected, b.requiredPermission(module, operation, emptyMap()))
        }
    }

    @Test fun `unknown operations answer unsupported`() {
        val b = bridge()
        val response = b.handle("tree", request(module = "window", operation = "explode"))
        assertEquals(NoxsApi.ERR_UNSUPPORTED_OPERATION, errorOf(response).first)
    }

    @Test fun `unwired modules answer unsupported instead of failing`() {
        val b = bridge() // no ports wired
        val response = b.handle("tree", request(module = "system", operation = "info"))
        assertEquals(NoxsApi.ERR_UNSUPPORTED_OPERATION, errorOf(response).first)
    }

    // ------------------------------------------------------ isolation

    @Test fun `package data dirs are isolated and path-safe`() {
        val b = bridge()
        val root = temporary.newFolder("pkgdata")
        val a = b.packageDataDir(root, "tree")
        val ab = b.packageDataDir(root, "tree.app")
        assertEquals(File(root, "tree"), a)
        assertEquals(File(root, "tree.app"), ab)
        assertFalse(a == ab)
    }

    @Test fun `package ids with traversal are refused`() {
        val b = bridge()
        val root = temporary.newFolder("pkgdata2")
        try {
            b.packageDataDir(root, "../escape")
            throw AssertionError("expected failure for traversal id")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------ error shape

    @Test fun `responses never leak internal details in messages`() {
        val b = bridge()
        val responses = listOf(
            b.handle("tree", "garbage"),
            b.handle("other", request(module = "window", operation = "open")),
            b.handle("tree", request(module = "terminal", operation = "execute"))
        )
        responses.forEach { response ->
            val (_, message) = errorOf(response)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("/data/"))
            assertFalse(message.contains(".kt"))
        }
    }
}
