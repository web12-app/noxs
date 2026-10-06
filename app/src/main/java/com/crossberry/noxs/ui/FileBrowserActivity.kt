/*
 * Noxs — original implementation.
 * Filesystem browser over the Noxs rootfs (app-private files only).
 */
package com.crossberry.noxs.ui

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crossberry.noxs.R
import com.crossberry.noxs.databinding.ActivityManagerBinding
import com.crossberry.noxs.runtime.NoxsPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private lateinit var paths: NoxsPaths
    private var cwd: File? = null
    private var adapter: TwoLineAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        paths = (application as com.crossberry.noxs.NoxsApplication).environments.activePaths()
        cwd = paths.rootfsHomeNoxs.takeIf { it.isDirectory } ?: paths.rootfs

        binding.screenTitle.text = getString(R.string.title_file_browser)
        binding.btnAction.setOnClickListener { render() }
        binding.inputBar.visibility = View.GONE

        adapter = TwoLineAdapter.bind(binding.recycler, emptyList())
        render()
    }

    private fun render() {
        val dir = cwd ?: return
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { rowsFor(dir) }
            adapter?.submit(rows)
            binding.screenEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            binding.screenEmpty.text = getString(R.string.files_empty)
        }
    }

    private fun rowsFor(dir: File): List<TwoLineRow> {
        val files = dir.listFiles()?.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ) ?: return emptyList()
        val rows = mutableListOf<TwoLineRow>()
        if (dir.absolutePath != paths.rootfs.absolutePath) {
            rows.add(TwoLineRow("…", dir.absolutePath.removePrefix(paths.rootfs.absolutePath).ifEmpty { "/" }) {
                cwd = dir.parentFile ?: paths.rootfs
                render()
            })
        }
        for (f in files) {
            val virtual = f.absolutePath.removePrefix(paths.rootfs.absolutePath).ifEmpty { "/" }
            val meta = if (f.isDirectory) "dir" else "${f.length() / 1024} KB"
            rows.add(
                TwoLineRow(
                    title = (if (f.isDirectory) "▸ " else "  ") + f.name,
                    subtitle = "$virtual · $meta",
                    onClick = {
                        if (f.isDirectory) {
                            cwd = f
                            render()
                        } else {
                            showFileViewer(f)
                        }
                    },
                    onLongClick = { confirmDelete(f) }
                )
            )
        }
        return rows
    }

    private fun showFileViewer(f: File) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { f.readText().take(8000) }.getOrElse { "(${it.javaClass.simpleName})" }
            }
            AlertDialog.Builder(this@FileBrowserActivity)
                .setTitle(f.name)
                .setMessage(text.ifBlank { "(empty)" })
                .setPositiveButton(R.string.action_close, null)
                .show()
        }
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_delete))
            .setMessage(f.name)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { f.deleteRecursively() }
                    if (!ok) Toast.makeText(this@FileBrowserActivity, R.string.err_generic, Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
