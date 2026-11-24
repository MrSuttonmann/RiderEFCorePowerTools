package com.efpt.rider.handlers.compare

import com.efpt.rider.json.DcJson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

// Ports of EFCorePowerTools.Handlers.Compare.{CompareType, CompareState, CompareAttributes, CompareLogModel}.
//
// Rider: in VS the enum labels go through EnumToLabelConverter, which looks the [Display] names up in
// CompareLocale - but CompareLocale has no such keys, so VS shows empty cells. The [Display] names
// themselves are used here as labels (with the "DeleteBehabior" typo fixed).

enum class CompareType(val label: String) {
    NoSet("Not set"),
    MatchAnything("MatchAnything"),
    DbContext("DbContext"),
    Entity("Entity"),
    Property("Property"),
    Database("Database"),
    Table("Table"),
    Column("Column"),
    PrimaryKey("Primary key"),
    ForeignKey("Foreign key"),
    Index("Index"),
}

enum class CompareState(val label: String) {
    Debug("Debug"),
    Ok(""),
    NotChecked("Not checked"),
    Different("Different"),
    NotInDatabase("NotInDatabase"),
    ExtraInDatabase("NotInContext"),
}

enum class CompareAttributes(val label: String) {
    NotSet(""),
    MatchAnything("Anything"),
    ColumnName("ColumnName"),
    ColumnType("ColumnType"),
    Nullability("Nullability"),
    DefaultValueSql("SqlDefaultValue"),
    ComputedColumnSql("SqlComputedColumn"),
    PersistentComputedColumn("PersistentComputedColumn"),
    ValueGenerated("ValueGenerated"),
    TableName("TableName"),
    PrimaryKey("PrimaryKey"),
    ConstraintName("ConstraintName"),
    IndexConstraintName("IndexConstraintName"),
    Unique("Unique"),
    DeleteBehavior("DeleteBehavior"),
    NotMappedToDatabase("NotMappedToDatabase"),
}

class CompareLogModel(
    val subLogs: List<CompareLogModel>,
    val type: CompareType,
    val state: CompareState,
    val name: String?,
    val attribute: CompareAttributes,
    val expected: String?,
    val found: String?,
) {
    companion object {
        /** Newtonsoft.Json DeserializeObject<List<CompareLogModel>>: enums may be numbers or names. */
        fun listFromJson(json: String): List<CompareLogModel> {
            val element = DcJson.parse(json)
            if (!element.isJsonArray) return emptyList()
            return element.asJsonArray.filter { it.isJsonObject }.map { fromJson(it.asJsonObject) }
        }

        private fun fromJson(o: JsonObject): CompareLogModel = CompareLogModel(
            subLogs = o.prop("SubLogs")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filter { it.isJsonObject }?.map { fromJson(it.asJsonObject) } ?: emptyList(),
            type = enumValue(o.prop("Type"), CompareType.NoSet),
            state = enumValue(o.prop("State"), CompareState.Debug),
            name = o.string("Name"),
            attribute = enumValue(o.prop("Attribute"), CompareAttributes.NotSet),
            expected = o.string("Expected"),
            found = o.string("Found"),
        )

        /** Case-insensitive property lookup (Newtonsoft default). */
        private fun JsonObject.prop(name: String): JsonElement? =
            (get(name) ?: entrySet().firstOrNull { it.key.equals(name, true) }?.value)?.takeUnless { it.isJsonNull }

        private fun JsonObject.string(name: String): String? =
            prop(name)?.let { if (it.isJsonPrimitive) it.asString else it.toString() }

        private inline fun <reified E : Enum<E>> enumValue(element: JsonElement?, default: E): E {
            if (element == null || !element.isJsonPrimitive) return default
            val p = element.asJsonPrimitive
            val values = enumValues<E>()
            return if (p.isNumber) values.getOrElse(p.asInt) { default }
            else values.firstOrNull { it.name.equals(p.asString, true) } ?: default
        }
    }
}
