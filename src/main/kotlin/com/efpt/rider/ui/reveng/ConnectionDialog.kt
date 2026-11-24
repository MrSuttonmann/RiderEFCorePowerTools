package com.efpt.rider.ui.reveng

import com.efpt.rider.handlers.reveng.RevEngLocale
import com.efpt.rider.model.CodeGenerationMode
import com.efpt.rider.model.ConnectionOrigin
import com.efpt.rider.model.DatabaseConnectionModel
import com.efpt.rider.model.DatabaseType
import com.efpt.rider.model.SchemaInfo
import com.efpt.rider.tools.EfRevEngLauncher
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.Component
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Port of ConnectionDialog / PickConnectionViewModel ("Add Custom Connection"): a name, a provider and an
 * ADO.NET connection string. Call on the EDT; returns the connection (not yet saved) or null.
 *
 * Rider: Visual Studio offers only MySQL and Snowflake here (other providers come from the DDEX "Add
 * Database Connection" dialog). Rider has no DDEX, so every provider supported by EF Core Power Tools
 * is offered, and a "Test Connection" button validates the string with the bundled efreveng runner.
 */
object ConnectionDialog {
    fun show(parent: Component): DatabaseConnectionModel? {
        val dialog = Dialog(parent)
        return if (dialog.showAndGet()) dialog.result() else null
    }

    val providers: List<DatabaseType> = listOf(
        DatabaseType.SQLServer,
        DatabaseType.SQLite,
        DatabaseType.Npgsql,
        DatabaseType.Mysql,
        DatabaseType.Oracle,
        DatabaseType.Firebird,
        DatabaseType.Snowflake,
    )

    /** CmdDatabaseType_SelectionChanged samples (MySQL and Snowflake are the VS texts; the others are Rider additions). */
    fun sample(type: DatabaseType?): String = when (type) {
        DatabaseType.Mysql -> "Server=myServerAddress;Database=myDataBase;Uid=myUsername;Pwd=myPassword;"
        DatabaseType.Snowflake -> "ACCOUNT=<accountid>;DB=MYDATABASE;SCHEMA=MYSCHEMA;USER=MYUSERNAME;PASSWORD=myPassword"
        DatabaseType.SQLServer -> "Data Source=myServer;Initial Catalog=myDataBase;Integrated Security=True;TrustServerCertificate=True"
        DatabaseType.SQLite -> "Data Source=C:\\data\\mydb.db"
        DatabaseType.Npgsql -> "Host=myServer;Database=myDataBase;Username=myUsername;Password=myPassword"
        DatabaseType.Oracle -> "User Id=myUsername;Password=myPassword;Data Source=myServer:1521/myService"
        DatabaseType.Firebird -> "DataSource=localhost;Database=C:\\data\\mydb.fdb;User=SYSDBA;Password=masterkey"
        else -> ""
    }

    private class Dialog(parentComponent: Component) : DialogWrapper(parentComponent, false) {
        private val nameField = JBTextField()
        private val typeModel = CollectionComboBoxModel(providers.toMutableList(), providers[0])
        private val typeCombo = ComboBox(typeModel).apply {
            renderer = object : com.intellij.ui.ColoredListCellRenderer<DatabaseType>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out DatabaseType>, value: DatabaseType?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value?.displayName ?: "")
                }
            }
        }
        private val connectionStringArea = JBTextArea(4, 40).apply { lineWrap = true; wrapStyleWord = false }
        private val sampleField = JBTextField().apply { isEditable = false; isFocusable = true }
        private val testButton = JButton("Test Connection")

        init {
            title = RevEngLocale.AddAdhocDatabaseConnection
            typeCombo.addActionListener { sampleField.text = sample(typeModel.selected) }
            sampleField.text = sample(typeModel.selected)
            testButton.addActionListener { testConnection() }
            init()
        }

        override fun createCenterPanel(): JComponent = panel {
            row(RevEngLocale.ConnectionName) {
                cell(nameField).align(AlignX.FILL).resizableColumn()
                cell(typeCombo)
            }
            row { label(RevEngLocale.ConnectionString) }
            row { cell(JBScrollPane(connectionStringArea)).align(AlignX.FILL) }
            row(RevEngLocale.ConnectionSample) { cell(sampleField).align(AlignX.FILL) }
            row { cell(testButton) }
        }.apply { preferredSize = Dimension(560, preferredSize.height) }

        override fun getPreferredFocusedComponent() = nameField

        override fun doValidate(): ValidationInfo? {
            // Ok_CanExecute: name and connection string are required
            if (nameField.text.isNullOrBlank()) return ValidationInfo("", nameField)
            if (connectionStringArea.text.isNullOrBlank()) return ValidationInfo("", connectionStringArea)
            return null
        }

        fun result() = DatabaseConnectionModel(
            connectionName = nameField.text.trim(),
            connectionString = connectionStringArea.text.trim(),
            databaseType = typeModel.selected ?: DatabaseType.Undefined,
            origin = ConnectionOrigin.Stored,
        )

        private fun testConnection() {
            val cs = connectionStringArea.text.trim()
            val type = typeModel.selected ?: return
            if (cs.isEmpty()) return
            var error: Throwable? = null
            val completed = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                {
                    try {
                        // Use a schema filter that matches nothing: efreveng connects and returns an empty list.
                        EfRevEngLauncher(CodeGenerationMode.EFCore8)
                            .getTables(cs, type, listOf(SchemaInfo("__efpt_connection_test__")), false)
                    } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                        throw e
                    } catch (e: Throwable) {
                        error = e
                    }
                },
                "Testing connection",
                true,
                null,
                contentPanel,
            )
            if (!completed) return
            val failure = error
            if (failure == null) {
                Messages.showInfoMessage(contentPanel, "Connection succeeded.", RevEngLocale.AddAdhocDatabaseConnection)
            } else {
                Messages.showErrorDialog(contentPanel, failure.message ?: failure.toString(), RevEngLocale.AddAdhocDatabaseConnection)
            }
        }
    }
}
