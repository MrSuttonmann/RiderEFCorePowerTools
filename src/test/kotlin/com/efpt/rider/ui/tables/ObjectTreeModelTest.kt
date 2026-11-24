package com.efpt.rider.ui.tables

import com.efpt.rider.model.ColumnModel
import com.efpt.rider.model.ColumnNamer
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.ObjectType
import com.efpt.rider.model.Schema
import com.efpt.rider.model.SerializationTableModel
import com.efpt.rider.model.TableModel
import com.efpt.rider.model.TableRenamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of the essential cases of UnitTests/ViewModels (ObjectTreeViewModelTests & friends). */
class ObjectTreeModelTest {

    // ------------------------------------------------------------------ ObjectTreeViewModelTests

    @Test
    fun addObjects_emptyCollection() {
        val vm = ObjectTreeModel()
        vm.addObjects(emptyList(), null)
        assertTrue(vm.types.flatMap { it.schemas }.isEmpty())
    }

    @Test
    fun addObjects_collection() {
        val vm = ObjectTreeModel()
        val objects = databaseObjects().sortedWith(compareBy<TableModel>({ it.schema }, { it.name }))
        vm.addObjects(objects, replacers())
        val vmObjects = vm.objects.sortedWith(compareBy<TableItem>({ it.schema }, { it.name }))

        assertEquals(objects.size, vmObjects.size)
        for (i in objects.indices) {
            assertEquals(objects[i].name, vmObjects[i].name)
            assertEquals(objects[i].schema, vmObjects[i].schema)
            assertEquals(objects[i].objectType, vmObjects[i].objectType)
            objects[i].columns!!.forEachIndexed { j, c -> assertEquals(c.name, vmObjects[i].columns[j].name) }
            assertFalse(vmObjects[i].isSelected)
        }
    }

    @Test
    fun addObjects_groupedByTypeThenSchema() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        assertEquals(listOf("Tables", "Views", "Stored procedures"), vm.types.map { it.text })
        assertEquals(listOf("dbo", "__", "unit", null), vm.types[0].schemas.map { it.name })
    }

    @Test
    fun addObjects_replacers() {
        val vm = ObjectTreeModel()
        val replacers = replacers()
        vm.addObjects(databaseObjects(), replacers)
        vm.setSelectionState(true)
        val renamers = vm.getRenamedObjects()

        assertEquals(replacers.size, renamers.size)
        for (schema in replacers) {
            for (table in schema.tables.orEmpty()) {
                val obj = vm.objects.first { it.schema == schema.schemaName && it.name.equals(table.name, ignoreCase = true) }
                assertEquals(obj.newName, table.newName)
                for (column in table.columns.orEmpty()) {
                    assertEquals(obj.columns.first { it.name.equals(column.name, ignoreCase = true) }.newName, column.newName)
                }
            }
        }
    }

    @Test
    fun addObjects_replacers_doNotMutateCallersList() {
        val vm = ObjectTreeModel()
        val replacers = replacers()
        vm.addObjects(databaseObjects(), replacers)
        vm.getRenamedObjects()
        assertEquals("atlas", replacers[0].tables!![0].name)
        assertEquals(1, replacers[2].tables!!.size)
    }

    @Test
    fun addObjects_replacers_issue679() {
        val vm = ObjectTreeModel()
        val objects = listOf(
            TableModel("departmentdetail", null, DatabaseType.Mysql, ObjectType.Table, listOf(ColumnModel("DEPTCode"))),
            TableModel("employeedetail", null, DatabaseType.Mysql, ObjectType.Table, listOf(ColumnModel("EMPCode"))),
            TableModel("same", null, DatabaseType.Mysql, ObjectType.Table, listOf(ColumnModel("same"))),
        )
        val replacers = listOf(
            Schema().apply {
                schemaName = null
                useSchemaName = false
                tables = mutableListOf(
                    TableRenamer("departmentdetail", "DepartmentDetail", mutableListOf(ColumnNamer("DEPTCode", "DEPTCode"))),
                    TableRenamer("employeedetail", "EmployeeDetail", mutableListOf(ColumnNamer("EMPCode", "EMPCode"))),
                    TableRenamer("same", "same", mutableListOf()),
                )
            },
        )

        vm.addObjects(objects, replacers)
        vm.setSelectionState(true)
        val renamers = vm.getRenamedObjects()

        assertEquals(3, renamers[0].tables!!.size)
        assertEquals(1, renamers[0].tables!![0].columns!!.size)
        assertEquals(1, renamers[0].tables!![1].columns!!.size)
        assertEquals(0, renamers[0].tables!![2].columns!!.size)
    }

    @Test
    fun selectTables_emptyCollection() {
        val vm = ObjectTreeModel()
        vm.selectObjects(emptyList())
        assertTrue(vm.getSelectedObjects().isEmpty())
    }

    @Test
    fun selectTables_collection() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        val selected = selectedObjects()
        vm.selectObjects(selected)

        val result = vm.getSelectedObjects()
        assertEquals(selected.size, result.size)
        for (b in selected) {
            val a = result.first { it.name == b.name }
            assertEquals(b.objectType, a.objectType)
            assertEquals(b.excludedColumns, a.excludedColumns)
        }
        // column1 of [dbo].[Atlas] is excluded, the rest selected
        val atlas = vm.objects.first { it.name == "Atlas" }
        assertEquals(listOf(true, false, true), atlas.columns.map { it.isSelected })
    }

    @Test
    fun selectTables_excludedIndexesKept() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        vm.selectObjects(listOf(SerializationTableModel("[dbo].[Atlas]", ObjectType.Table, null, listOf("IX_1"))))
        assertEquals(listOf("IX_1"), vm.getSelectedObjects().single().excludedIndexes)
    }

    @Test
    fun selectTables_normalized() {
        val vm = ObjectTreeModel()
        val objects = databaseObjects()
        vm.addObjects(objects, null)
        val preselected = listOf(SerializationTableModel("dbo.Atlas", ObjectType.Table))
        vm.selectObjects(ObjectTreeModel.normalizeTables(preselected, ObjectTreeModel.shouldNormalize(objects)))
        assertEquals(listOf("[dbo].[Atlas]"), vm.getSelectedObjects().map { it.name })
        assertEquals("dbo.Atlas", preselected[0].name) // caller's object untouched
    }

    @Test
    fun normalizeTables() {
        val input = listOf(
            SerializationTableModel("dbo.Table.With.Dots", ObjectType.Table),
            SerializationTableModel("[dbo].[x]", ObjectType.Table),
        )
        assertEquals(listOf("[dbo].[Table.With.Dots]", "[dbo].[x]"), ObjectTreeModel.normalizeTables(input, true).map { it.name })
        assertEquals(listOf("dbo.Table.With.Dots", "[dbo].[x]"), ObjectTreeModel.normalizeTables(input, false).map { it.name })
    }

    @Test
    fun searchText_noDirectFilter() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        vm.search("ref", SearchMode.Text)
        assertEquals(databaseObjects().size, vm.objects.size)
        assertEquals(2, vm.objects.count { it.isVisible })
        vm.search("  ", SearchMode.Text)
        assertEquals(vm.objects.size, vm.objects.count { it.isVisible })
    }

    @Test
    fun search_regex() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        vm.search("^view\\d$", SearchMode.RegularExpression)
        assertEquals(listOf("view1", "view2"), vm.objects.filter { it.isVisible }.map { it.name })
        // invalid expression: tree left unchanged (VS: the Regex constructor throws)
        vm.search("[", SearchMode.RegularExpression)
        assertEquals(2, vm.objects.count { it.isVisible })
        // text mode accepts any characters
        vm.search("[", SearchMode.Text)
        assertEquals(0, vm.objects.count { it.isVisible })
    }

    @Test
    fun search_usesDisplayNameIncludingNewName() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        vm.objects.first { it.name == "foo" }.newName = "Bar"
        vm.search("bar", SearchMode.Text)
        assertEquals(listOf("foo"), vm.objects.filter { it.isVisible }.map { it.name })
        assertEquals("Bar - (foo)", vm.objects.first { it.name == "foo" }.displayName)
        assertTrue(vm.types[0].isVisible)
        assertFalse(vm.types[1].isVisible)
    }

    @Test
    fun getSelectedObjects_noneSelected() {
        val vm = ObjectTreeModel()
        assertTrue(vm.getSelectedObjects().isEmpty())
        vm.addObjects(databaseObjects(), null)
        assertTrue(vm.getSelectedObjects().isEmpty())
        assertFalse(vm.canOk())
    }

    @Test
    fun getSelectedObjects_allSelected() {
        val vm = ObjectTreeModel()
        val databaseObjects = databaseObjects()
        vm.addObjects(databaseObjects, null)
        vm.types.forEach { it.setSelected(true) }

        val result = vm.getSelectedObjects().map { it.name }.sorted()
        assertEquals(11, result.size)
        assertEquals(databaseObjects.map { it.displayName }.sorted(), result)
        assertEquals(0, vm.getRenamedObjects().size)
        assertTrue(vm.canOk())
    }

    @Test
    fun getSelectedObjects_partialSelection() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        val schemas = vm.types.flatMap { it.schemas }
        schemas.forEach { it.objects[0].setSelected(true) }
        val result = vm.getSelectedObjects()
        assertEquals(6, result.size)
        schemas.forEach { s -> assertTrue(result.any { it.name == s.objects[0].modelDisplayName }) }
    }

    @Test
    fun getSelectionState() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        vm.objects.forEach { it.setSelected(true) }
        assertEquals(true, vm.getSelectionState())
        vm.objects.forEach { it.setSelected(false) }
        assertEquals(false, vm.getSelectionState())
        vm.objects.first().setSelected(true)
        assertNull(vm.getSelectionState())
    }

    @Test
    fun setSelectionState() {
        for (selected in listOf(true, false)) {
            val vm = ObjectTreeModel()
            vm.addObjects(databaseObjects(), null)
            vm.setSelectionState(selected)
            assertEquals(if (selected) databaseObjects().size else 0, vm.getSelectedObjects().size)
        }
    }

    @Test
    fun selectionChanged_raisedOncePerOperation() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        var count = 0
        vm.onSelectionChanged = { count++ }
        vm.setSelectionState(true)
        assertEquals(1, count)
        vm.objects.first().setSelected(false)
        assertEquals(2, count)
        vm.objects.first().setSelected(false) // unchanged
        assertEquals(2, count)
    }

    @Test
    fun getRenamedObjects() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)

        val t0 = vm.types[0].schemas[0].objects[0]
        t0.setSelected(true)
        t0.newName = "NewTableName"
        t0.columns[0].newName = "NewColumnName"

        val t3 = vm.types[0].schemas[3].objects[0]
        t3.setSelected(true)
        t3.newName = "DepartmentDetail"
        t3.columns[0].setSelectedCommand(true)
        t3.columns[0].newName = "DepartmentName"

        val renamed = vm.getRenamedObjects()
        assertNotNull(renamed)
        assertSame("NewTableName", renamed.first().tables!![0].newName)
        assertSame("NewColumnName", renamed.first().tables!![0].columns!![0].newName)
        assertEquals("dbo", renamed.first().schemaName)
        assertSame("DepartmentDetail", renamed.last().tables!![0].newName)
        assertSame("DepartmentName", renamed.last().tables!![0].columns!![0].newName)
        assertNull(renamed.last().schemaName)
    }

    @Test
    fun getRenamedObjects_nothingSelected_keepsOriginalReplacers() {
        val vm = ObjectTreeModel()
        val replacers = replacers()
        vm.addObjects(databaseObjects(), replacers)
        // nothing selected: the patterns of "dbo" survive (no tables), "other" and the null schema come from the originals
        val renamed = vm.getRenamedObjects()
        assertEquals(listOf("dbo", "other", null), renamed.map { it.schemaName })
        assertTrue(renamed[0].tables!!.isEmpty())
        assertEquals("TableRegexPattern", renamed[0].tableRegexPattern)
        assertNull(renamed[1].tables) // empty Tables list dropped
        assertEquals("departmentdetail", renamed[2].tables!!.single().name)
    }

    // ------------------------------------------------------------------ Table / Column / Schema / Root item tests

    @Test
    fun table_isSelected_selectsColumns() {
        for (isSelected in listOf(true, false)) {
            val vm = ObjectTreeModel()
            vm.addObjects(databaseObjects(), null)
            val table = vm.objects.first { it.name == "Atlas" }
            table.setSelected(!isSelected)
            table.setSelected(isSelected)
            assertTrue(table.columns.all { it.isSelected == isSelected && it.isTableSelected == isSelected })
        }
    }

    @Test
    fun table_reselecting_resetsExcludedColumns() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        val table = vm.objects.first { it.name == "Atlas" }
        table.setSelected(true)
        table.columns[1].setSelectedCommand(false)
        table.setSelected(true) // no change
        assertFalse(table.columns[1].isSelected)
        table.setSelected(false)
        table.setSelected(true)
        assertTrue(table.columns[1].isSelected)
    }

    @Test
    fun table_objectTypeIcon() {
        val vm = ObjectTreeModel()
        vm.addObjects(
            listOf(
                TableModel("a", "dbo", DatabaseType.SQLServer, ObjectType.Table, listOf(ColumnModel("Id", isPrimaryKey = true))),
                TableModel("b", "dbo", DatabaseType.SQLServer, ObjectType.Table, listOf(ColumnModel("Id"))),
                TableModel("c", "dbo", DatabaseType.SQLServer, ObjectType.View, listOf(ColumnModel("Id"))),
                TableModel("d", "dbo", DatabaseType.SQLServer, ObjectType.Procedure, null),
                TableModel("e", "dbo", DatabaseType.SQLServer, ObjectType.ScalarFunction, null),
            ),
            null,
        )
        assertEquals(
            listOf(ObjectTypeIcon.Table, ObjectTypeIcon.TableWithoutKey, ObjectTypeIcon.View, ObjectTypeIcon.Procedure, ObjectTypeIcon.ScalarFunction),
            vm.objects.map { it.objectTypeIcon },
        )
    }

    @Test
    fun column_primaryAndForeignKey_cannotBeExcluded() {
        val vm = ObjectTreeModel()
        vm.addObjects(
            listOf(
                TableModel(
                    "t", "dbo", DatabaseType.SQLServer, ObjectType.Table,
                    listOf(ColumnModel("Id", isPrimaryKey = true), ColumnModel("FkId", isForeignKey = true), ColumnModel("Name")),
                ),
            ),
            null,
        )
        val table = vm.objects.single()
        val (pk, fk, plain) = table.columns

        // table not selected: nothing can be changed
        plain.setSelectedCommand(true)
        assertFalse(plain.isSelected)

        table.setSelected(true)
        pk.setSelectedCommand(false)
        fk.setSelectedCommand(false)
        plain.setSelectedCommand(false)
        assertTrue(pk.isSelected)
        assertTrue(fk.isSelected)
        assertFalse(plain.isSelected)
        assertFalse(pk.isEnabled)
        assertFalse(pk.isColumn)
        assertEquals(listOf("Name"), vm.getSelectedObjects().single().excludedColumns)
    }

    @Test
    fun column_displayName_and_cancelEdit() {
        val c = ColumnItem("col", isPrimaryKey = false, isForeignKey = false)
        assertEquals("col", c.displayName)
        c.newName = "Col"
        assertEquals("Col - (col)", c.displayName)
        c.cancelEdit()
        assertEquals("col", c.newName)
    }

    @Test
    fun schemaAndRoot_triState() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        val type = vm.types[0]
        val schema = type.schemas[0] // dbo: Atlas, __RefactorLog, sysdiagrams
        assertEquals(false, schema.isSelected)
        assertEquals(false, type.isSelected)

        schema.objects[0].setSelected(true)
        assertNull(schema.isSelected)
        assertNull(type.isSelected)

        schema.setSelected(true)
        assertEquals(true, schema.isSelected)
        assertNull(type.isSelected)

        type.setSelected(true)
        assertEquals(true, type.isSelected)
        assertTrue(type.schemas.all { it.isSelected == true })

        type.setSelected(false)
        assertEquals(false, type.isSelected)
        assertTrue(vm.objects.filter { it.objectType == ObjectType.Table }.none { it.isSelected })
    }

    @Test
    fun schemaSelection_includesObjectsHiddenBySearch() {
        val vm = ObjectTreeModel()
        vm.addObjects(databaseObjects(), null)
        vm.search("Atlas", SearchMode.Text)
        vm.types[0].schemas[0].setSelected(true)
        assertEquals(3, vm.getSelectedObjects().size)
    }

    // ------------------------------------------------------------------ data (ObjectTreeViewModelTests)

    private fun columnsWithId() = listOf(ColumnModel("Id", null, true, false), ColumnModel("column1"), ColumnModel("column2"))

    private fun columnsWithoutId() = listOf(ColumnModel("Id"), ColumnModel("column1"), ColumnModel("column2"))

    private fun databaseObjects(): List<TableModel> = listOf(
        TableModel("Atlas", "dbo", DatabaseType.SQLServer, ObjectType.Table, columnsWithId()),
        TableModel("RefactorLog", "__", DatabaseType.SQLServer, ObjectType.Table, columnsWithId()),
        TableModel("__RefactorLog", "dbo", DatabaseType.SQLServer, ObjectType.Table, columnsWithId()),
        TableModel("sysdiagrams", "dbo", DatabaseType.SQLServer, ObjectType.Table, columnsWithId()),
        TableModel("test", "unit", DatabaseType.SQLServer, ObjectType.Table, columnsWithId()),
        TableModel("foo", "unit", DatabaseType.SQLServer, ObjectType.Table, columnsWithId()),
        TableModel("view1", "views", DatabaseType.SQLServer, ObjectType.View, columnsWithoutId()),
        TableModel("view2", "views", DatabaseType.SQLServer, ObjectType.View, columnsWithoutId()),
        TableModel("procedure1", "stored", DatabaseType.SQLServer, ObjectType.Procedure, emptyList()),
        TableModel("procedure2", "stored", DatabaseType.SQLServer, ObjectType.Procedure, emptyList()),
        TableModel(
            "departmentdetail", null, DatabaseType.Mysql, ObjectType.Table,
            listOf(ColumnModel("departmentname"), ColumnModel("DEPTCode"), ColumnModel("Id", null, true, false)),
        ),
    )

    private fun selectedObjects() = listOf(
        SerializationTableModel("[dbo].[Atlas]", ObjectType.Table, listOf("column1"), null),
        SerializationTableModel("[unit].[test]", ObjectType.Table, emptyList(), null),
        SerializationTableModel("[unit].[foo]", ObjectType.Table, emptyList(), null),
        SerializationTableModel("[views].[view1]", ObjectType.View, emptyList(), null),
        SerializationTableModel("[stored].[procedure2]", ObjectType.Procedure, emptyList(), null),
    )

    private fun replacers(): List<Schema> = listOf(
        Schema().apply {
            columnPatternReplaceWith = "ColumnPatternReplaceWith"
            columnRegexPattern = "ColumnRegexPattern"
            schemaName = "dbo"
            tablePatternReplaceWith = "TablePatternReplaceWith"
            tableRegexPattern = "TableRegexPattern"
            tables = mutableListOf(TableRenamer("atlas", "newatlas", mutableListOf(ColumnNamer("column1", "newcolumn1"))))
        },
        Schema().apply {
            schemaName = "other"
            useSchemaName = true
        },
        Schema().apply {
            schemaName = null
            useSchemaName = false
            tables = mutableListOf(
                TableRenamer(
                    "departmentdetail", "DepartmentDetail",
                    mutableListOf(ColumnNamer("departmentname", "DepartmentName"), ColumnNamer("DEPTCode", "DEPTCode")),
                ),
            )
        },
    )
}
