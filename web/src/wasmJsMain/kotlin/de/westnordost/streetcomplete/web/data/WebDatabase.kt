package de.westnordost.streetcomplete.web.data

import de.westnordost.streetcomplete.web.data.ConflictAlgorithm.ABORT
import de.westnordost.streetcomplete.web.data.ConflictAlgorithm.FAIL
import de.westnordost.streetcomplete.web.data.ConflictAlgorithm.IGNORE
import de.westnordost.streetcomplete.web.data.ConflictAlgorithm.REPLACE
import de.westnordost.streetcomplete.web.data.ConflictAlgorithm.ROLLBACK
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The web implementation of the shared [Database] contract (milestone M1 of
 * docs/pwa-port/ROADMAP.md, resolving docs/pwa-port/adr/0001-web-database.md).
 *
 * ## Why this shape
 *
 * The shared [Database] interface is **synchronous** — `query(...)` returns a `List`, `insert(...)`
 * returns a `Long`, etc. The browser's durable, worker-only OPFS sync-access API can't satisfy that
 * on the main thread (ADR 0001). This implementation takes the ADR's pragmatic **Option 3**: run
 * [sql.js](https://sql.js.org) — SQLite compiled to WebAssembly — **in memory on the main thread**.
 * Once `initSqlJs()` has loaded (awaited once at startup, see [create]), every sql.js call is
 * genuinely synchronous, so the whole `Database` surface is satisfied with no interface change.
 *
 * Durability is layered on **asynchronously and out of band**: after each mutation the in-memory
 * database image is exported and written to IndexedDB (debounced, see [markDirty]); on startup the
 * image is loaded back before the first query. So the DB survives reloads without the main thread
 * ever blocking on storage.
 *
 * **Trade-off (documented in ADR 0001):** the whole database lives in memory and is flushed as one
 * image, so this is right for the current preview and moderate data, but not for the full downloaded
 * OSM dataset. The durable-at-scale path — the shared data layer in a Web Worker over an OPFS
 * sync-access VFS (ADR Option 1) — is the follow-up; it will replace this class behind the exact
 * same [Database] interface.
 *
 * ## The JS boundary
 *
 * As with `map/WebMap.kt`, **all JavaScript stays behind this file.** The `js(...)` interop
 * functions at the bottom are the only code that touches sql.js / IndexedDB; everything above talks
 * to them through a tiny, typed boundary. To keep marshalling robust on Kotlin/Wasm, values cross as
 * **JSON strings** (`kotlinx.serialization`): bind parameters go out as a JSON array, result rows
 * come back as a JSON array of objects. Blobs, which JSON can't hold, are wrapped as
 * `{"__blob":"<base64>"}` on both sides.
 */
class WebDatabase private constructor(
    private val handle: JsAny,
    private val dbName: String,
    private val scope: CoroutineScope,
) : Database {

    /** SQLite has no nested transactions; only the outermost BEGIN/COMMIT is issued. */
    private var transactionDepth = 0
    private var persistJob: Job? = null

    override fun exec(sql: String, args: Array<Any>?) {
        if (args == null) sqlRun(handle, sql) else sqlRunWithParams(handle, sql, paramsToJson(args.asList()))
        markDirty()
    }

    override fun <T> rawQuery(sql: String, args: Array<Any>?, transform: (CursorPosition) -> T): List<T> {
        val json = sqlQuery(handle, sql, paramsToJson(args?.asList().orEmpty()))
        return parseRows(json).map { transform(JsonCursorPosition(it)) }
    }

    override fun <T> queryOne(
        table: String,
        columns: Array<String>?,
        where: String?,
        args: Array<Any>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
        transform: (CursorPosition) -> T
    ): T? =
        rawQuery(buildSelect(false, table, columns, where, groupBy, having, orderBy, 1), args, transform)
            .firstOrNull()

    override fun <T> query(
        table: String,
        columns: Array<String>?,
        where: String?,
        args: Array<Any>?,
        groupBy: String?,
        having: String?,
        orderBy: String?,
        limit: Int?,
        distinct: Boolean,
        transform: (CursorPosition) -> T
    ): List<T> =
        rawQuery(buildSelect(distinct, table, columns, where, groupBy, having, orderBy, limit), args, transform)

    override fun insert(
        table: String,
        values: Collection<Pair<String, Any?>>,
        conflictAlgorithm: ConflictAlgorithm?
    ): Long {
        val columns = values.joinToString(",") { it.first }
        val placeholders = values.joinToString(",") { "?" }
        val sql = "INSERT${conflictAlgorithm.toSQL()} INTO $table ($columns) VALUES ($placeholders)"
        sqlRunWithParams(handle, sql, paramsToJson(values.map { it.second }))
        markDirty()
        // Mirror Android's semantics: a conflict that inserted nothing returns -1 rather than a
        // stale last_insert_rowid() from a previous insert.
        return if (sqlChanges(handle) == 0) -1L else sqlLastInsertRowId(handle).toLong()
    }

    override fun insertMany(
        table: String,
        columnNames: Array<String>,
        valuesList: Iterable<Array<Any?>>,
        conflictAlgorithm: ConflictAlgorithm?
    ): List<Long> = transaction {
        valuesList.map { row ->
            require(row.size == columnNames.size)
            insert(table, columnNames.zip(row.toList()), conflictAlgorithm)
        }
    }

    override fun update(
        table: String,
        values: Collection<Pair<String, Any?>>,
        where: String?,
        args: Array<Any>?,
        conflictAlgorithm: ConflictAlgorithm?
    ): Int {
        val assignments = values.joinToString(",") { "${it.first} = ?" }
        val whereClause = if (where != null) " WHERE $where" else ""
        val sql = "UPDATE${conflictAlgorithm.toSQL()} $table SET $assignments$whereClause"
        val params = values.map { it.second } + args?.asList().orEmpty()
        sqlRunWithParams(handle, sql, paramsToJson(params))
        markDirty()
        return sqlChanges(handle)
    }

    override fun delete(table: String, where: String?, args: Array<Any>?): Int {
        val whereClause = if (where != null) " WHERE $where" else ""
        sqlRunWithParams(handle, "DELETE FROM $table$whereClause", paramsToJson(args?.asList().orEmpty()))
        markDirty()
        return sqlChanges(handle)
    }

    override fun <T> transaction(block: () -> T): T {
        if (transactionDepth == 0) sqlRun(handle, "BEGIN")
        transactionDepth++
        try {
            val result = block()
            transactionDepth--
            if (transactionDepth == 0) {
                sqlRun(handle, "COMMIT")
                markDirty()
            }
            return result
        } catch (e: Throwable) {
            transactionDepth--
            if (transactionDepth == 0) sqlRun(handle, "ROLLBACK")
            throw e
        }
    }

    /**
     * Persist the current in-memory image to IndexedDB, debounced so a burst of writes (or a whole
     * transaction) flushes once. Runs off the hot path — callers never wait on storage.
     */
    private fun markDirty() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            sqlPersist(handle, dbName).await()
        }
    }

    companion object {
        /**
         * sql.js is loaded as a global `<script>` from a CDN by index.html (like maplibre). Keep this
         * version in sync with that tag. It only matters at runtime — nothing here is a build
         * dependency — so an offline launch simply finds it absent (see [isSqlJsLoaded]).
         */
        private const val CDN_BASE = "https://unpkg.com/sql.js@1.13.0/dist/"
        private const val PERSIST_DEBOUNCE_MS = 300L

        /** Whether the sql.js global finished loading (it is a CDN `<script>`; may be offline). */
        fun isSqlJsLoaded(): Boolean = sqlJsLoaded()

        /**
         * Load sql.js, open (or restore from IndexedDB) the database named [dbName], and return a
         * ready, synchronous [Database]. Suspends only here, at startup; every later call is sync.
         *
         * Call [isSqlJsLoaded] first — this throws if the sql.js global is absent (offline launch),
         * so the caller can degrade to "no database" without crashing the shell.
         */
        suspend fun create(dbName: String, scope: CoroutineScope): WebDatabase {
            val sqlModule = sqlInit(CDN_BASE).await()
            val handle = sqlOpen(sqlModule, dbName).await()
            return WebDatabase(handle, dbName, scope)
        }
    }
}

// --- Value marshalling ------------------------------------------------------------------------
// Kotlin <-> JS values cross as JSON. Blobs become {"__blob":"<base64>"} since JSON has no binary.

@OptIn(ExperimentalEncodingApi::class)
private fun paramsToJson(values: List<Any?>): String = buildJsonArray {
    for (v in values) {
        when (v) {
            null -> add(JsonNull)
            is String -> add(JsonPrimitive(v))
            is Boolean -> add(JsonPrimitive(if (v) 1 else 0))
            is Short -> add(JsonPrimitive(v.toInt()))
            is Int -> add(JsonPrimitive(v))
            is Long -> add(JsonPrimitive(v))
            is Float -> add(JsonPrimitive(v))
            is Double -> add(JsonPrimitive(v))
            is ByteArray -> add(buildJsonObject { put(BLOB_KEY, JsonPrimitive(Base64.encode(v))) })
            else -> throw IllegalArgumentException("Cannot bind value of type ${v::class.simpleName}")
        }
    }
}.toString()

private fun parseRows(json: String): List<JsonObject> =
    Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }

private const val BLOB_KEY = "__blob"

/** [CursorPosition] over one result row decoded from the JSON boundary. */
private class JsonCursorPosition(private val row: JsonObject) : CursorPosition {
    override fun getInt(columnName: String): Int = value(columnName).jsonPrimitive.int
    override fun getLong(columnName: String): Long = value(columnName).jsonPrimitive.long
    override fun getDouble(columnName: String): Double = value(columnName).jsonPrimitive.double
    override fun getFloat(columnName: String): Float = value(columnName).jsonPrimitive.float
    override fun getString(columnName: String): String = value(columnName).jsonPrimitive.content
    override fun getBlob(columnName: String): ByteArray = decodeBlob(value(columnName))

    override fun getIntOrNull(columnName: String): Int? = valueOrNull(columnName)?.jsonPrimitive?.int
    override fun getLongOrNull(columnName: String): Long? = valueOrNull(columnName)?.jsonPrimitive?.long
    override fun getDoubleOrNull(columnName: String): Double? = valueOrNull(columnName)?.jsonPrimitive?.double
    override fun getFloatOrNull(columnName: String): Float? = valueOrNull(columnName)?.jsonPrimitive?.float
    override fun getStringOrNull(columnName: String): String? = valueOrNull(columnName)?.jsonPrimitive?.content
    override fun getBlobOrNull(columnName: String): ByteArray? = valueOrNull(columnName)?.let(::decodeBlob)

    private fun value(columnName: String) =
        row[columnName] ?: throw IllegalArgumentException("No such column: $columnName")

    /** null when the column is absent from the row or its value is SQL NULL. */
    private fun valueOrNull(columnName: String) = row[columnName]?.takeUnless { it is JsonNull }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeBlob(element: kotlinx.serialization.json.JsonElement): ByteArray =
        Base64.decode(element.jsonObject.getValue(BLOB_KEY).jsonPrimitive.content)
}

// --- SQL building -----------------------------------------------------------------------------

private fun buildSelect(
    distinct: Boolean,
    table: String,
    columns: Array<String>?,
    where: String?,
    groupBy: String?,
    having: String?,
    orderBy: String?,
    limit: Int?,
): String = buildString {
    append("SELECT ")
    if (distinct) append("DISTINCT ")
    append(columns?.joinToString(",") ?: "*")
    append(" FROM ").append(table)
    if (where != null) append(" WHERE ").append(where)
    if (groupBy != null) append(" GROUP BY ").append(groupBy)
    if (having != null) append(" HAVING ").append(having)
    if (orderBy != null) append(" ORDER BY ").append(orderBy)
    if (limit != null) append(" LIMIT ").append(limit)
}

private fun ConflictAlgorithm?.toSQL(): String = when (this) {
    ROLLBACK -> " OR ROLLBACK"
    ABORT -> " OR ABORT"
    FAIL -> " OR FAIL"
    IGNORE -> " OR IGNORE"
    REPLACE -> " OR REPLACE"
    null -> ""
}

// --- Interop with sql.js + IndexedDB ----------------------------------------------------------
// Each body is a single js(...) expression — the Kotlin/Wasm interop form — so sql.js and
// IndexedDB types never enter Kotlin. The database handle crosses the boundary as an opaque JsAny;
// all values cross as JSON strings (see the marshalling section above).

/** Whether sql.js finished loading. */
private fun sqlJsLoaded(): Boolean =
    js("typeof initSqlJs !== 'undefined'")

/** Initialise the sql.js WebAssembly module, fetching `sql-wasm.wasm` from [cdnBase]. */
private fun sqlInit(cdnBase: String): Promise<JsAny> =
    js("initSqlJs({ locateFile: function(f) { return cdnBase + f; } })")

/** Open [dbName]'s image from IndexedDB (or create an empty DB). Resilient: any storage failure
 *  falls back to a fresh in-memory database rather than rejecting. */
private fun sqlOpen(sqlModule: JsAny, dbName: String): Promise<JsAny> = js(
    """
    (function() {
      return new Promise(function(resolve) {
        try {
          var open = indexedDB.open('streetcomplete-web-db', 1);
          open.onupgradeneeded = function() { open.result.createObjectStore('images'); };
          open.onerror = function() { resolve(new sqlModule.Database()); };
          open.onsuccess = function() {
            try {
              var idb = open.result;
              var tx = idb.transaction('images', 'readonly');
              var get = tx.objectStore('images').get(dbName);
              get.onsuccess = function() {
                var bytes = get.result;
                idb.close();
                resolve(bytes ? new sqlModule.Database(new Uint8Array(bytes)) : new sqlModule.Database());
              };
              get.onerror = function() { idb.close(); resolve(new sqlModule.Database()); };
            } catch (e) { resolve(new sqlModule.Database()); }
          };
        } catch (e) { resolve(new sqlModule.Database()); }
      });
    })()
    """
)

/** Export the in-memory image and store it in IndexedDB under [dbName]. Never rejects. */
private fun sqlPersist(db: JsAny, dbName: String): Promise<JsAny?> = js(
    """
    (function() {
      return new Promise(function(resolve) {
        try {
          var data = db.export();
          var open = indexedDB.open('streetcomplete-web-db', 1);
          open.onupgradeneeded = function() { open.result.createObjectStore('images'); };
          open.onerror = function() { resolve(null); };
          open.onsuccess = function() {
            try {
              var idb = open.result;
              var tx = idb.transaction('images', 'readwrite');
              tx.objectStore('images').put(data, dbName);
              tx.oncomplete = function() { idb.close(); resolve(null); };
              tx.onerror = function() { idb.close(); resolve(null); };
            } catch (e) { resolve(null); }
          };
        } catch (e) { resolve(null); }
      });
    })()
    """
)

/** Execute a statement (or statements) with no bind parameters and no result. */
private fun sqlRun(db: JsAny, sql: String): JsAny? =
    js("db.run(sql)")

/** Execute a statement with JSON-encoded bind parameters (blobs as {\"__blob\":base64}). */
private fun sqlRunWithParams(db: JsAny, sql: String, paramsJson: String): JsAny? = js(
    """
    (function() {
      var params = JSON.parse(paramsJson).map(sc_decodeParam);
      db.run(sql, params);
    })()
    """
)

/** Run a query and return its rows as a JSON array of objects (blobs as {\"__blob\":base64}). */
private fun sqlQuery(db: JsAny, sql: String, paramsJson: String): String =
    js(
        """
        (function() {
          var params = JSON.parse(paramsJson).map(sc_decodeParam);
          var stmt = db.prepare(sql);
          stmt.bind(params);
          var rows = [];
          while (stmt.step()) { rows.push(stmt.getAsObject()); }
          stmt.free();
          return JSON.stringify(rows, function(key, value) {
            if (value instanceof Uint8Array) { return { __blob: sc_encodeBlob(value) }; }
            return value;
          });
        })()
        """
    )

/** Row id of the most recent successful insert. */
private fun sqlLastInsertRowId(db: JsAny): Double =
    js("(function() { var r = db.exec('SELECT last_insert_rowid() AS id'); return (r.length && r[0].values.length) ? r[0].values[0][0] : 0; })()")

/** Number of rows changed by the most recent statement. */
private fun sqlChanges(db: JsAny): Int =
    js("db.getRowsModified()")

/**
 * Install the small JS helpers the boundary functions above rely on (base64 <-> Uint8Array for
 * blob marshalling). Called once from [main] before any DB use; kept out of the per-call bodies so
 * they stay single expressions.
 */
fun installDatabaseInterop() {
    scInstallInterop()
}

private fun scInstallInterop(): JsAny? = js(
    """
    (function() {
      globalThis.sc_encodeBlob = function(u8) {
        var s = ''; var CHUNK = 0x8000;
        for (var i = 0; i < u8.length; i += CHUNK) {
          s += String.fromCharCode.apply(null, u8.subarray(i, i + CHUNK));
        }
        return btoa(s);
      };
      globalThis.sc_decodeParam = function(v) {
        if (v && typeof v === 'object' && v.__blob !== undefined) {
          var bin = atob(v.__blob);
          var arr = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) { arr[i] = bin.charCodeAt(i); }
          return arr;
        }
        return v;
      };
    })()
    """
)
