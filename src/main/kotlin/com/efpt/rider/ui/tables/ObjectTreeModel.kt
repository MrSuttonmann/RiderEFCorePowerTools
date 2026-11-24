package com.efpt.rider.ui.tables

import com.efpt.rider.model.ColumnNamer
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.Schema
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.model.TableModel
import com.efpt.rider.model.TableRenamer
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** Mirrors Contracts.ViewModels.SearchMode. */
enum class SearchMode { Text, RegularExpression }

/** Mirrors Contracts.ViewModels.ObjectTypeIcon. */
enum class ObjectTypeIcon { Table, TableWithoutKey, View, Procedure, ScalarFunction }

/** English texts of the ReverseEngineerLocale / SharedLocale strings used by the picker. */
internal object PickTablesTexts {
    const val CHOOSE_DATABASE_OBJECTS = "Choose Your Database Objects"
    const val TABLES = "Tables"
    const val VIEWS = "Views"
    const val STORED_PROCEDURES = "Stored procedures"
    const val FUNCTIONS = "Functions"
    const val SEARCH = "Search"
    const val SEARCH_FOR_OBJECTS = "Search for objects"
    const val USE_TEXT_SEARCH = "Use text search"
    const val USE_REGULAR_EXPRESSION = "Use regular expression"
    const val CANNOT_UNCHECK_PRIMARY_KEY = "This column is part of the primary key and cannot be unchecked if the table is selected."
    const val SQLITE_EXPERIENCE = "Improve your SQLite experience"
    const val INSTALL_SQLITE_TOOLBOX = "Install SQLite Toolbox"
    const val SQLITE_TOOLBOX_URL = "https://marketplace.visualstudio.com/items?itemName=ErikEJ.SQLServerCompactSQLiteToolbox"
    const val UNCHECKING_FILE_REMOVED = "Unchecking an item may cause the related file to be removed"
    const val NEW_NAME_CANNOT_BE_EMPTY = "New name cannot be empty"
    const val RENAME = "Rename"

    // Rider: VS only disables the OK button (no text); the wizard host needs a message for its Next validation.
    const val NOTHING_SELECTED = "Select at least one table, view, stored procedure or function"
}

/** Port of ColumnInformationViewModel. */
class ColumnItem(val name: String, val isPrimaryKey: Boolean, val isForeignKey: Boolean) {
    var newName: String = name
    var isSelected: Boolean = false
        private set
    var isTableSelected: Boolean = false
        internal set
    internal lateinit var table: TableItem

    val isEnabled: Boolean get() = isTableSelected && !isPrimaryKey && !isForeignKey
    val isColumn: Boolean get() = !isPrimaryKey
    val displayName: String get() = if (newName == name) name else "$newName - ($name)"

    /** ColumnInformationViewModel.SetSelected (unconditional). */
    fun setSelected(value: Boolean) {
        isSelected = value
    }

    /** SetSelectedCommand: only when the column is enabled (table selected, not part of a key). */
    fun setSelectedCommand(value: Boolean) {
        if (isEnabled) {
            isSelected = value
        }
    }

    /** CancelEditCommand. */
    fun cancelEdit() {
        newName = name
    }

    override fun toString() = displayName
}

/** Port of TableInformationViewModel. */
class TableItem(
    val name: String,
    val modelDisplayName: String,
    val schema: String?,
    val objectType: ObjectType,
    private val owner: ObjectTreeModel,
) {
    var newName: String = name
    var excludedIndexes: List<String>? = null
    var isVisible: Boolean = true
        internal set
    val columns: MutableList<ColumnItem> = mutableListOf()
    internal lateinit var parent: SchemaItem

    var isSelected: Boolean = false
        private set

    val displayName: String get() = if (newName == name) name else "$newName - ($name)"
    val hasPrimaryKey: Boolean get() = columns.any { it.isPrimaryKey }

    val objectTypeIcon: ObjectTypeIcon
        get() = if (objectType == ObjectType.Table && !hasPrimaryKey) ObjectTypeIcon.TableWithoutKey else iconOf(objectType)

    /** SetSelectedCommand: selecting / deselecting a table (re)selects or deselects all of its columns. */
    fun setSelected(value: Boolean) {
        if (value == isSelected) {
            return
        }

        owner.batch {
            isSelected = value
            for (column in columns) {
                column.isTableSelected = value
                column.setSelected(value)
            }
            parent.childSelectionChanged(if (value) 1 else -1)
            owner.markChanged()
        }
    }

    /** CancelEditCommand. */
    fun cancelEdit() {
        newName = name
    }

    override fun toString() = displayName
}

/** Port of SchemaInformationViewModel. */
class SchemaItem(val name: String?, internal val replacingSchema: Schema) {
    val objects: MutableList<TableItem> = mutableListOf()
    private var selectedCount = 0

    /** true = all objects selected, false = none, null = some. */
    val isSelected: Boolean?
        get() = when (selectedCount) {
            objects.size -> true
            0 -> false
            else -> null
        }

    val isVisible: Boolean get() = objects.any { it.isVisible }

    fun setSelected(value: Boolean) {
        if (isSelected == value) {
            return
        }
        objects.forEach { it.setSelected(value) }
    }

    internal fun childSelectionChanged(delta: Int) {
        selectedCount += delta
    }

    override fun toString() = name ?: ""
}

/** Port of ObjectTreeRootItemViewModel. */
class TypeItem(val text: String, val objectType: ObjectType) {
    val schemas: MutableList<SchemaItem> = mutableListOf()

    val isSelected: Boolean?
        get() = when {
            schemas.all { it.isSelected == true } -> true
            schemas.all { it.isSelected == false } -> false
            else -> null
        }

    val isVisible: Boolean get() = schemas.any { it.isVisible }

    val objectTypeIcon: ObjectTypeIcon get() = iconOf(objectType)

    fun setSelected(value: Boolean) {
        if (isSelected == value) {
            return
        }
        schemas.forEach { it.setSelected(value) }
    }

    override fun toString() = text
}

private fun iconOf(objectType: ObjectType): ObjectTypeIcon = when (objectType) {
    ObjectType.Table -> ObjectTypeIcon.Table
    ObjectType.View -> ObjectTypeIcon.View
    ObjectType.Procedure -> ObjectTypeIcon.Procedure
    ObjectType.ScalarFunction -> ObjectTypeIcon.ScalarFunction
}

/**
 * Port of ObjectTreeViewModel (+ the selection logic of PickTablesViewModel). Pure logic, no Swing:
 * not thread safe, used from the EDT only.
 */
class ObjectTreeModel {
    val types: MutableList<TypeItem> = mutableListOf()
    private var allSchemas: List<Schema> = emptyList()

    /** ObjectSelectionChanged: raised once per (batched) selection operation. */
    var onSelectionChanged: (() -> Unit)? = null

    private var batchDepth = 0
    private var changed = false

    val objects: List<TableItem> get() = types.flatMap { t -> t.schemas.flatMap { it.objects } }

    internal fun <T> batch(block: () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            batchDepth--
            if (batchDepth == 0 && changed) {
                changed = false
                onSelectionChanged?.invoke()
            }
        }
    }

    internal fun markChanged() {
        if (batchDepth > 0) changed = true else onSelectionChanged?.invoke()
    }

    fun clear() {
        types.clear()
        allSchemas = emptyList()
    }

    /** IsInEditMode is tracked by the Swing editor; this mirrors GetSelectionState. */
    fun getSelectionState(): Boolean? {
        val all = objects
        return when {
            all.all { it.isSelected } -> true
            all.none { it.isSelected } -> false
            else -> null
        }
    }

    fun setSelectionState(value: Boolean) = batch {
        objects.forEach { it.setSelected(value) }
    }

    /** OkCommand.CanExecute: at least a single table, view, function or stored procedure must be selected. */
    fun canOk(): Boolean = objects.any { it.isSelected }

    fun search(searchText: String?, searchMode: SearchMode) {
        val all = objects
        val visibility = computeVisibility(all.map { it.displayName }, searchText, searchMode) ?: return
        applyVisibility(all, visibility)
    }

    internal fun applyVisibility(items: List<TableItem>, visibility: BooleanArray) {
        items.forEachIndexed { i, item -> item.isVisible = visibility[i] }
    }

    fun getSelectedObjects(): List<SerializationTableModel> = objects
        .filter { it.isSelected }
        .map { m ->
            SerializationTableModel(
                m.modelDisplayName,
                m.objectType,
                m.columns.filter { !it.isSelected }.map { it.name },
                m.excludedIndexes?.toList(),
            )
        }

    fun getRenamedObjects(): List<Schema> {
        val result = mutableListOf<Schema>()

        val groups = LinkedHashMap<String?, MutableList<SchemaItem>>()
        for (schema in types.flatMap { it.schemas }) {
            groups.getOrPut(schema.name) { mutableListOf() }.add(schema)
        }

        for ((name, schemaItems) in groups) {
            val replacingSchema = schemaItems.first().replacingSchema
            replacingSchema.schemaName = name
            replacingSchema.tables?.clear()

            for (obj in schemaItems.flatMap { s -> s.objects.filter { it.isSelected } }) {
                val objectIsRenamed = obj.name != obj.newName
                val renamedColumns = obj.columns.filter { it.name != it.newName && it.isSelected }

                val originalReplacers = allSchemas
                    .filter { it.schemaName == name }
                    .flatMap { a -> a.tables.orEmpty().filter { t -> t.columns != null && t.name == obj.name } }

                val ignoredColumnReplacers = originalReplacers
                    .flatMap { o -> o.columns.orEmpty().filter { c -> c.name != null && c.name == c.newName } }

                val ignoredTableReplacers = originalReplacers
                    .filter { c -> c.name != null && c.name == c.newName && c.columns?.isEmpty() == true }

                if (objectIsRenamed ||
                    renamedColumns.isNotEmpty() ||
                    ignoredColumnReplacers.isNotEmpty() ||
                    ignoredTableReplacers.isNotEmpty()
                ) {
                    val columnRenamers = renamedColumns.map { ColumnNamer(it.name, it.newName) } + ignoredColumnReplacers

                    val tables = replacingSchema.tables ?: mutableListOf<TableRenamer>().also { replacingSchema.tables = it }
                    tables.add(TableRenamer(obj.name, obj.newName, columnRenamers.toMutableList()))
                }
            }

            if (replacingSchema.columnPatternReplaceWith != null ||
                replacingSchema.columnRegexPattern != null ||
                replacingSchema.tablePatternReplaceWith != null ||
                replacingSchema.tableRegexPattern != null ||
                replacingSchema.tables?.isNotEmpty() == true ||
                replacingSchema.useSchemaName
            ) {
                result.add(replacingSchema)
            }
        }

        if (allSchemas.isEmpty()) {
            return result
        }

        for (schema in allSchemas) {
            if (result.none { it.schemaName == schema.schemaName }) {
                if (schema.tables?.isEmpty() == true) {
                    schema.tables = null
                }
                result.add(schema)
            }
        }

        return result
    }

    fun addObjects(objects: List<TableModel>, customReplacers: List<Schema>?) {
        // Rider: VS hands the caller's Schema instances to the schema view models and mutates them in
        // GetRenamedObjects; we work on deep copies so the caller's list is never modified.
        val replacers = customReplacers?.map { deepCopy(it) }
        allSchemas = customReplacers?.map { deepCopy(it) } ?: emptyList()

        val objectTypes = listOf(
            ObjectType.Table to PickTablesTexts.TABLES,
            ObjectType.View to PickTablesTexts.VIEWS,
            ObjectType.Procedure to PickTablesTexts.STORED_PROCEDURES,
            ObjectType.ScalarFunction to PickTablesTexts.FUNCTIONS,
        )

        batch {
            for ((objectType, text) in objectTypes) {
                val objectsBySchema = objects.filter { it.objectType == objectType }.groupBy { it.schema }
                if (objectsBySchema.isEmpty()) {
                    continue
                }

                val type = TypeItem(text, objectType)
                types.add(type)

                for ((schemaName, schemaObjects) in objectsBySchema) {
                    val schemaReplacer = replacers?.firstOrNull { it.schemaName == schemaName }
                    val objectReplacers = schemaReplacer?.tables

                    val svm = SchemaItem(schemaName, schemaReplacer ?: Schema())
                    type.schemas.add(svm)

                    for (obj in schemaObjects) {
                        val objectReplacer = objectReplacers?.firstOrNull { it.name != null && it.name.equals(obj.name, ignoreCase = true) }
                        val tvm = TableItem(obj.name, obj.displayName, obj.schema, obj.objectType, this)
                        tvm.newName = objectReplacer?.newName ?: obj.name
                        tvm.parent = svm

                        val columnReplacers = objectReplacer?.columns
                        if (obj.objectType.hasColumns) {
                            for (column in obj.columns.orEmpty()) {
                                val cvm = ColumnItem(column.name, column.isPrimaryKey, column.isForeignKey)
                                cvm.newName = columnReplacers
                                    ?.firstOrNull { it.name != null && it.name.equals(column.name, ignoreCase = true) }
                                    ?.newName ?: column.name
                                cvm.table = tvm
                                tvm.columns.add(cvm)
                            }
                        }

                        predefineSelection(tvm)
                        svm.objects.add(tvm)
                    }
                }
            }
        }
    }

    fun selectObjects(objects: List<SerializationTableModel>) {
        val byName = HashMap<String, SerializationTableModel>()
        for (o in objects) {
            byName.putIfAbsent(o.name, o)
        }

        batch {
            for (obj in this.objects) {
                val t = byName[obj.modelDisplayName]
                obj.excludedIndexes = t?.excludedIndexes
                obj.setSelected(t != null)
                if (obj.objectType.hasColumns && obj.isSelected) {
                    for (column in obj.columns) {
                        column.setSelected(t?.excludedColumns?.any { it == column.name } != true)
                    }
                }
            }
        }
    }

    private fun predefineSelection(t: TableItem) {
        val unSelect = t.name.startsWith("[__") ||
            t.name.startsWith("[dbo].[__") ||
            t.name.endsWith(".[sysdiagrams]")
        if (unSelect) {
            t.setSelected(false)
        }
    }

    companion object {
        /** Regex match timeout of ObjectTreeViewModel.Search (3 seconds). */
        private const val REGEX_TIMEOUT_NANOS = 3_000_000_000L

        /**
         * The visibility computation of ObjectTreeViewModel.Search, usable off the EDT.
         * Returns null when the regular expression is invalid (VS then leaves the tree unchanged).
         */
        fun computeVisibility(displayNames: List<String>, searchText: String?, searchMode: SearchMode): BooleanArray? {
            val blank = searchText.isNullOrBlank()
            if (searchMode == SearchMode.Text || blank) {
                return BooleanArray(displayNames.size) { blank || displayNames[it].contains(searchText!!, ignoreCase = true) }
            }

            val pattern = try {
                Pattern.compile(searchText!!)
            } catch (_: PatternSyntaxException) {
                return null
            }

            return BooleanArray(displayNames.size) {
                try {
                    pattern.matcher(TimeoutCharSequence(displayNames[it], System.nanoTime() + REGEX_TIMEOUT_NANOS)).find()
                } catch (_: RegexTimeoutException) {
                    true
                }
            }
        }

        /** ReverseEngineerHelper.NormalizeTables (on copies): "schema.name" -> "[schema].[name]" for SQL Server. */
        fun normalizeTables(tables: List<SerializationTableModel>, shouldFix: Boolean): List<SerializationTableModel> =
            tables.map { table ->
                if (shouldFix && !table.name.startsWith("[")) {
                    table.copy().also { it.name = "[" + table.name.replaceFirst(".", "].[") + "]" }
                } else {
                    table
                }
            }

        /** Normalization VS applies to the preselected tables: only for (live) SQL Server connections. */
        fun shouldNormalize(tables: List<TableModel>): Boolean = tables.any { it.databaseType == DatabaseType.SQLServer }

        internal fun deepCopy(s: Schema): Schema = Schema().also { c ->
            c.useSchemaName = s.useSchemaName
            c.schemaName = s.schemaName
            c.tableRegexPattern = s.tableRegexPattern
            c.tablePatternReplaceWith = s.tablePatternReplaceWith
            c.columnRegexPattern = s.columnRegexPattern
            c.columnPatternReplaceWith = s.columnPatternReplaceWith
            c.tables = s.tables?.map { t ->
                TableRenamer(t.name, t.newName, t.columns?.map { ColumnNamer(it.name, it.newName) }?.toMutableList())
            }?.toMutableList()
        }
    }
}

private class RegexTimeoutException : RuntimeException(null, null, false, false)

/** Emulates the .NET Regex match timeout: aborts a (catastrophically backtracking) match after the deadline. */
private class TimeoutCharSequence(private val inner: CharSequence, private val deadline: Long) : CharSequence {
    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        if (System.nanoTime() > deadline) throw RegexTimeoutException()
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        TimeoutCharSequence(inner.subSequence(startIndex, endIndex), deadline)

    override fun toString() = inner.toString()
}
