package core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.github.salomonbrys.kodein.*
import filter.*
import gs.environment.*
import gs.property.*
import org.obsolete.combine
import org.obsolete.downloadFilters
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.Properties

abstract class Filters {
    abstract val filters: IProperty<List<Filter>>
    abstract val filtersCompiled: IProperty<Set<String>>
    abstract val apps: IProperty<List<App>>

    // Those do not change during lifetime of the app
    abstract val filterConfig: IProperty<FilterConfig>
}

class FiltersImpl(
        private val kctx: Worker,
        private val xx: Environment,
        private val ctx: Context
) : Filters() {

    private val pages: Pages by xx.instance()

    override val filterConfig = newProperty<FilterConfig>(kctx, { ctx.inject().instance() })

    private val filtersRefresh = { it: List<Filter> ->
        val c = filterConfig()
        val serialiser: FilterSerializer = ctx.inject().instance()
        val builtinFilters = try {
            serialiser.deserialise(load({ openUrl(pages.filters(), c.fetchTimeoutMillis) }))
        } catch (e: Exception) {
            // We may make this request exactly while establishing VPN, erroring out. Simply wait a bit.
            Thread.sleep(3000)
            serialiser.deserialise(load({ openUrl(pages.filters(), c.fetchTimeoutMillis) }))
        }

        val newFilters = if (it.isEmpty()) {
            // First preselect
            builtinFilters
        } else {
            // Update existing filters just in case
            it.map { filter ->
                val newFilter = builtinFilters.find { it == filter }
                if (newFilter != null) {
                    newFilter.active = filter.active
                    newFilter.localised = filter.localised
                    newFilter
                } else filter
            }
        }

        // Try to fetch localised copy for filters if available
        val prop = Properties()
        prop.load(InputStreamReader(openUrl(pages.filtersStrings(), c.fetchTimeoutMillis), Charset.forName("UTF-8")))
        newFilters.forEach { try {
            it.localised = LocalisedFilter(
                    name = prop.getProperty("${it.id}_name")!!,
                    comment = prop.getProperty("${it.id}_comment")
            )
        } catch (e: Exception) {}}

        newFilters
    }

    override val filters = newPersistedProperty(kctx,
            persistence = AFiltersPersistence(ctx, { filtersRefresh(emptyList()) }),
            zeroValue = { emptyList() },
            refresh = filtersRefresh,
            shouldRefresh = {
                val c = filterConfig()
                val now = ctx.inject().instance<Time>().now()
                when {
                    !isCacheValid(c.cacheFile, c.cacheTTLMillis, now) -> true
                    it.isEmpty() -> true
                // TODO: maybe check if we have connectivity (assuming we can trust it)
                    else -> false
                }
            }
    )

    override val filtersCompiled = newPersistedProperty(kctx,
            persistence = ACompiledFiltersPersistence(ctx),
            zeroValue = { emptySet() },
            refresh = {
                val selected = filters().filter(Filter::active)
                downloadFilters(selected)
                val selectedBlacklist = selected.filter { !it.whitelist }
                val selectedWhitelist = selected.filter(Filter::whitelist)

                combine(selectedBlacklist, selectedWhitelist)
            },
            shouldRefresh = {
                val c = filterConfig()
                val now = ctx.inject().instance<Time>().now()
                when {
                    !isCacheValid(c.cacheFile, c.cacheTTLMillis, now) -> true
                    it.isEmpty() -> true
                    else -> false
                }
            }
    )

    private val appsRefresh = {
        val installed = ctx.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        installed.map {
            App(
                    appId = it.packageName,
                    label = ctx.packageManager.getApplicationLabel(it).toString(),
                    system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }.sortedBy { it.label }
    }

    override val apps = newProperty(kctx, zeroValue = { appsRefresh() }, refresh = { appsRefresh() })
}


fun newFiltersModule(ctx: Context): Kodein.Module {
    return Kodein.Module {
        bind<Filters>() with singleton { FiltersImpl(kctx = with("gscore").instance(10), xx = lazy,
                ctx = ctx) }
        bind<IHostlineProcessor>() with singleton { DefaultHostlineProcessor() }
        bind<IFilterSource>() with factory { sourceId: String ->
            val cfg: FilterConfig = instance()
            val processor: IHostlineProcessor = instance()

            when (sourceId) {
                "link" -> FilterSourceLink(cfg.fetchTimeoutMillis, processor)
                "file" -> FilterSourceUri(ctx = instance(), processor = instance())
                "app" -> FilterSourceApp(ctx = instance())
                else -> FilterSourceSingle()
            }}
        bind<FilterSerializer>() with singleton {
            FilterSerializer(i18n = instance(),
                    sourceProvider = { type: String -> with(type).instance<IFilterSource>() })
        }
        bind<FilterConfig>() with singleton {
            FilterConfig(
                    cacheFile = File(getPersistencePath(ctx).absoluteFile, "filters"),
                    exportFile = getPublicPersistencePath("blokada-export")
                            ?: File(getPersistencePath(ctx).absoluteFile, "blokada-export"),
                    cacheTTLMillis = 1 * 24 * 60 * 60 * 100L, // A
                    fetchTimeoutMillis = 10 * 1000
            )
        }
        bind<AFilterAddDialog>() with provider {
            AFilterAddDialog(ctx,
                    sourceProvider = { type: String -> with(type).instance<IFilterSource>() }
            )
        }
        bind<AFilterGenerateDialog>(true) with provider {
            AFilterGenerateDialog(ctx,
                    s = instance(),
                    sourceProvider = { type: String -> with(type).instance<IFilterSource>() },
                    whitelist = true
            )
        }
        bind<AFilterGenerateDialog>(false) with provider {
            AFilterGenerateDialog(ctx,
                    s = instance(),
                    sourceProvider = { type: String -> with(type).instance<IFilterSource>() },
                    whitelist = false
            )
        }
        onReady {
            val s: Filters = instance()
            val t: Tunnel = instance()
            val j: Journal = instance()

            // Reload engine in case whitelisted apps selection changes
            var currentApps = listOf<Filter>()
            s.filters.doWhenSet().then {
                val newApps = s.filters().filter { it.whitelist && it.active && it.source is FilterSourceApp }
                if (newApps != currentApps) {
                    currentApps = newApps

                    if (!t.enabled()) {
                    } else if (t.active()) {
                        t.restart %= true
                        t.active %= false
                    } else {
                        t.retries.refresh()
                        t.restart %= false
                        t.active %= true
                    }
                }
            }

            // Compile filters every time they change
            s.filters.doWhenSet().then {
                s.filtersCompiled.refresh(force = true)
            }

            // Push filters to engine every time they're changed
            val engine: IEngineManager = instance()
            s.filtersCompiled.doWhenSet().then {
                engine.updateFilters()
            }

            // On locale change, refresh all localised content
            val i18n: I18n = instance()

            i18n.locale.doWhenChanged().then {
                j.log("refresh filters from locale change")
                s.filters.refresh(force = true)
            }

            // Refresh filters list whenever system apps switch is changed
            val ui: UiState = instance()
            ui.showSystemApps.doWhenChanged().then {
                s.filters %= s.filters()
            }

            s.filtersCompiled {}
        }
    }
}

data class Filter(
        val id: String,
        val source: IFilterSource,
        val credit: String? = null,
        var valid: Boolean = false,
        var active: Boolean = false,
        var whitelist: Boolean = false,
        var hosts: List<String> = emptyList(),
        var localised: LocalisedFilter? = null
) {

    override fun hashCode(): Int {
        return source.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Filter) return false
        return source.equals(other.source)
    }
}

data class App(
        val appId: String,
        val label: String,
        val system: Boolean
)

data class LocalisedFilter(
        val name: String,
        val comment: String? = null
)


data class FilterConfig(
        val cacheFile: File,
        val exportFile: File,
        val cacheTTLMillis: Long,
        val fetchTimeoutMillis: Int
)

class AFiltersPersistence(
        val ctx: Context,
        val default: () -> List<Filter>
) : Persistence<List<Filter>> {

    val p by lazy { ctx.getSharedPreferences("filters", Context.MODE_PRIVATE) }

    override fun read(current: List<Filter>): List<Filter> {
        val s : FilterSerializer = ctx.inject().instance()
        val filters = s.deserialise(p.getString("filters", "").split("^"))
        return if (filters.isNotEmpty()) filters else default()
    }

    override fun write(source: List<Filter>) {
        val s : FilterSerializer = ctx.inject().instance()
        val e = p.edit()
        e.putInt("migratedVersion", 20)
        e.putString("filters", s.serialise(source).joinToString("^"))
        e.apply()
    }

}

interface IFilterSource {
    fun fetch(): List<String>
    fun fromUserInput(vararg string: String): Boolean
    fun toUserInput(): String
    fun serialize(): String
    fun deserialize(string: String, version: Int): IFilterSource
    fun id(): String
}


class ACompiledFiltersPersistence(
        val ctx: Context
) : Persistence<Set<String>> {

    private val cache by lazy { ctx.inject().instance<FilterConfig>().cacheFile }

    override fun read(current: Set<String>): Set<String> {
        return try { readFromCache(cache).toSet() } catch (e: Exception) { setOf() }
    }

    override fun write(source: Set<String>) {
        saveToCache(source, cache)
    }

}

