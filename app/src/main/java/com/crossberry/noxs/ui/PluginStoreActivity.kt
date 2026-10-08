/*
 * Noxs — original implementation.
 * PluginStoreActivity: the Noxs Plugin Store. Search, All/Installed/Updates
 * filters, plugin cards (logo, name, description, version, category, action)
 * and the entry point shared by the sidebar and `nx plug`.
 *
 * Every state comes from NoxsPluginManager — the same service the CLI uses.
 * Nothing here hardcodes plugin metadata.
 */
package com.crossberry.noxs.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.crossberry.noxs.R
import com.crossberry.noxs.runtime.plugins.InstalledPlugin
import com.crossberry.noxs.runtime.plugins.NoxsPluginManager
import com.crossberry.noxs.runtime.plugins.NoxsPluginRuntime
import com.crossberry.noxs.runtime.plugins.RegistryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class PluginStoreActivity : AppCompatActivity() {

    enum class Filter { ALL, INSTALLED, UPDATES }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: NoxsPluginManager? = null

    private lateinit var adapter: PluginCardAdapter
    private lateinit var statusView: TextView
    private lateinit var searchInput: EditText
    private lateinit var chipRow: LinearLayout
    private val chipViews = mutableMapOf<Filter, TextView>()

    private var filter: Filter = Filter.ALL
    private var query: String = ""
    private var cards: List<NoxsPluginManager.StoreCard> = emptyList()
    private val busyIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val dp: Float
        get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, resources.displayMetrics)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = pluginManager()
        buildUi()
        adapter = PluginCardAdapter(
            onCardClick = { card -> openDetails(card.entry.id) },
            onActionClick = { card -> runAction(card.entry.id) }
        )
        contentList.adapter = adapter
        contentList.layoutManager = LinearLayoutManager(this)
        wireChips()
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        load()
    }

    // --------------------------------------------------------------- data

    private fun load() {
        statusView.text = getString(R.string.plugin_store_loading)
        statusView.visibility = TextView.VISIBLE
        scope.launch(Dispatchers.IO) {
            val loaded = manager?.let { m ->
                runCatching { m.storeCards(refresh = true) }.getOrDefault(emptyList())
            } ?: emptyList()
            scope.launch {
                cards = loaded
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        val q = query.trim().lowercase()
        val visible = cards.filter { card ->
            val matchesQuery = q.isEmpty() ||
                card.entry.name.lowercase().contains(q) ||
                card.entry.id.lowercase().contains(q) ||
                card.entry.description.lowercase().contains(q) ||
                (card.entry.category?.lowercase()?.contains(q) ?: false) ||
                card.entry.keywords.any { it.lowercase().contains(q) }
            val matchesFilter = when (filter) {
                Filter.ALL -> true
                Filter.INSTALLED -> card.installed != null
                Filter.UPDATES -> card.updateAvailable
            }
            matchesQuery && matchesFilter
        }
        adapter.submit(visible, installedById(), busyIds)
        statusView.visibility = if (visible.isEmpty() && cards.isNotEmpty() || cards.isEmpty()) {
            statusView.text = when {
                cards.isEmpty() -> getString(R.string.plugin_store_empty)
                visible.isEmpty() && filter == Filter.UPDATES -> getString(R.string.plugin_store_no_updates)
                else -> getString(R.string.plugin_store_no_results)
            }
            TextView.VISIBLE
        } else {
            TextView.GONE
        }
    }

    private fun installedById(): Map<String, InstalledPlugin> =
        cards.mapNotNull { it.installed }.associateBy { it.meta.id }

    // ------------------------------------------------------------- actions

    /** One action per card: install / update / open / enable — by state. */
    private fun runAction(id: String) {
        val card = cards.firstOrNull { it.entry.id == id } ?: return
        val local = card.installed
        val mgr = manager ?: return
        when {
            busyIds.contains(id) -> return
            local == null -> install(id)
            card.updateAvailable -> install(id, update = true)
            !local.enabled -> {
                runCatching { mgr.enable(id) }
                    .onFailure { toast(getString(R.string.plugin_action_failed)) }
                    .onSuccess {
                        toast(getString(R.string.plugin_enabled_toast, local.meta.name))
                        load()
                    }
            }
            else -> openPlugin(local)
        }
    }

    private fun install(id: String, update: Boolean = false) {
        val mgr = manager ?: return
        busyIds.add(id)
        applyFilter()
        toast(getString(R.string.plugin_installing_toast))
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching { if (update) mgr.update(id) else mgr.install(id) }
            scope.launch {
                busyIds.remove(id)
                outcome
                    .onSuccess { installed ->
                        // Load the plugin right after a successful install.
                        NoxsPluginRuntime.activate(applicationContext, installed)
                        toast(getString(R.string.plugin_installed_toast, installed.meta.name))
                    }
                    .onFailure {
                        toast(getString(R.string.plugin_install_failed_toast))
                    }
                load()
            }
        }
    }

    private fun openPlugin(plugin: InstalledPlugin) {
        if (!NoxsPluginRuntime.isActive(plugin.meta.id)) {
            NoxsPluginRuntime.activate(applicationContext, plugin)
            Toast.makeText(this, getString(R.string.plugin_opened_toast), Toast.LENGTH_SHORT).show()
        }
        if (NoxsPluginRuntime.activePluginIds().none { it == plugin.meta.id }) {
            toast(getString(R.string.plugin_action_failed))
        }
    }

    private fun openDetails(id: String) {
        startActivity(Intent(this, PluginDetailsActivity::class.java)
            .putExtra(PluginDetailsActivity.EXTRA_PLUGIN_ID, id))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ UI

    private lateinit var contentList: RecyclerView

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B1016.toInt())
        }
        setContentView(root)

        // Header: "Plugins" + close.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
        }
        header.addView(TextView(this).apply {
            text = getString(R.string.plugin_store_title)
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 19f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.action_close)
            setTextColor(0xFF9AA7B4.toInt())
            textSize = 13f
            setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            setOnClickListener { finish() }
        })
        root.addView(header)

        // Search box.
        searchInput = EditText(this).apply {
            hint = getString(R.string.plugin_store_search_hint)
            setSingleLine(true)
            setTextColor(0xFFE6EDF3.toInt())
            setHintTextColor(0xFF5A6875.toInt())
            setBackgroundColor(0xFF151C24.toInt())
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
        }
        root.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins((14 * dp).toInt(), (2 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt()) })

        // Filter chips: All / Installed / Updates.
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        }
        root.addView(chipRow)

        // Status (loading / empty / no results).
        statusView = TextView(this).apply {
            setTextColor(0xFF8FA0AF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
        }
        root.addView(statusView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // List.
        contentList = RecyclerView(this).apply { setBackgroundColor(0x00000000) }
        root.addView(contentList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { setMargins(0, (4 * dp).toInt(), 0, 0) })
    }

    private fun wireChips() {
        listOf(
            Filter.ALL to R.string.plugin_filter_all,
            Filter.INSTALLED to R.string.plugin_filter_installed,
            Filter.UPDATES to R.string.plugin_filter_updates
        ).forEach { (filterId, label) ->
            val chip = TextView(this).apply {
                text = getString(label)
                textSize = 13f
                setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins((4 * dp).toInt(), 0, (4 * dp).toInt(), 0) }
                setOnClickListener { selectFilter(filterId) }
            }
            chipViews[filterId] = chip
            chipRow.addView(chip)
        }
        selectFilter(Filter.ALL)
    }

    private fun selectFilter(newFilter: Filter) {
        filter = newFilter
        chipViews.forEach { (filterId, chip) ->
            val selected = filterId == filter
            chip.background = GradientDrawable().apply {
                cornerRadius = 14f * dp
                setColor(if (selected) 0xFF24313D.toInt() else 0x0015171B)
                setStroke(
                    (1 * dp).toInt(),
                    if (selected) 0xFF3A4A59.toInt() else 0x002F3946
                )
            }
            chip.setTextColor(if (selected) 0xFF7CE8B8.toInt() else 0xFF8FA0AF.toInt())
        }
        applyFilter()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun pluginManager(): NoxsPluginManager? = runCatching {
        val app = application as com.crossberry.noxs.NoxsApplication
        val paths = app.environments.activePaths()
        NoxsPluginManager(
            rootfsHome = paths.rootfsHomeNoxs,
            cacheDir = app.cacheDir,
            appVersion = appVersion(),
            guestBinDir = java.io.File(paths.rootfs, "usr/local/bin")
        )
    }.onFailure {
        com.crossberry.noxs.shared.NoxsLog.w("PluginStore", "manager unavailable: ${it.javaClass.simpleName}")
    }.getOrNull()

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "0.0.0"
}

/**
 * Store list adapter. Each card shows exactly what the registry entry says:
 * logo (fetched from logoUrl), name, description, version + category and a
 * state-aware action button.
 */
class PluginCardAdapter(
    private val onCardClick: (NoxsPluginManager.StoreCard) -> Unit,
    private val onActionClick: (NoxsPluginManager.StoreCard) -> Unit
) : RecyclerView.Adapter<PluginCardAdapter.Holder>() {

    private var items: List<NoxsPluginManager.StoreCard> = emptyList()
    private var installed: Map<String, InstalledPlugin> = emptyMap()
    private var busy: Set<String> = emptySet()
    private val logoCache = ConcurrentHashMap<String, android.graphics.Bitmap>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    class Holder(val row: LinearLayout) : RecyclerView.ViewHolder(row)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val dp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, parent.resources.displayMetrics
        )
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12f * dp
                setColor(0xFF121922.toInt())
                setStroke((1 * dp).toInt(), 0xFF22303C.toInt())
            }
        }
        return Holder(row)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = items[position]
        val context = holder.row.context
        val dp = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics
        )
        val entry = card.entry
        holder.row.setOnClickListener { onCardClick(card) }

        holder.row.removeAllViews()

        // Logo: cached bitmap, fetched on demand, initial-letter fallback.
        val logo = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()).apply {
                marginEnd = (12 * dp).toInt()
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = 10f * dp
                setColor(0xFF0E141B.toInt())
            }
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
        }
        holder.row.addView(logo)
        bindLogo(logo, entry)

        // Name / description / version + category.
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textColumn.addView(TextView(context).apply {
            text = entry.name
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 15f
        })
        textColumn.addView(TextView(context).apply {
            text = entry.description
            setTextColor(0xFF8FA0AF.toInt())
            textSize = 12f
            maxLines = 2
        })
        textColumn.addView(TextView(context).apply {
            text = "v${entry.version}" + (entry.category?.let { "  •  $it" } ?: "")
            setTextColor(0xFF6B7A88.toInt())
            textSize = 11f
        })
        holder.row.addView(textColumn)

        // Action button — state aware.
        val actionLabel = when {
            busy.contains(entry.id) -> context.getString(R.string.plugin_action_working)
            card.installed == null -> context.getString(R.string.plugin_action_install)
            card.updateAvailable -> context.getString(R.string.plugin_action_update)
            !card.installed.enabled -> context.getString(R.string.plugin_action_enable)
            else -> context.getString(R.string.plugin_action_open)
        }
        holder.row.addView(TextView(context).apply {
            text = actionLabel
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 10f * dp
                setColor(
                    when {
                        busy.contains(entry.id) -> 0xFF1A222C.toInt()
                        card.installed == null -> 0xFF1F4D38.toInt()
                        card.updateAvailable -> 0xFF4D3A1F.toInt()
                        else -> 0xFF1A222C.toInt()
                    }
                )
            }
            setTextColor(
                when {
                    card.updateAvailable -> 0xFFF0C674.toInt()
                    card.installed == null -> 0xFF7CE8B8.toInt()
                    else -> 0xFF9AA7B4.toInt()
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (8 * dp).toInt() }
            setOnClickListener { onActionClick(card) }
        })
    }

    private fun bindLogo(view: ImageView, entry: RegistryEntry) {
        val url = entry.logoUrl ?: return
        logoCache[url]?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageResource(android.R.drawable.ic_menu_manage)
        if (!inFlight.add(url)) return
        Thread {
            val bitmap = runCatching {
                val bytes = com.crossberry.noxs.runtime.plugins.PluginHttp.get(
                    url, 10_000, 1L * 1024L * 1024L
                )
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (bitmap != null) logoCache[url] = bitmap
            inFlight.remove(url)
            view.post {
                logoCache[url]?.let { view.setImageBitmap(it) }
            }
        }.start()
    }

    fun submit(
        items: List<NoxsPluginManager.StoreCard>,
        installed: Map<String, InstalledPlugin>,
        busy: Set<String>
    ) {
        this.items = items
        this.installed = installed
        this.busy = busy
        notifyDataSetChanged()
    }
}
