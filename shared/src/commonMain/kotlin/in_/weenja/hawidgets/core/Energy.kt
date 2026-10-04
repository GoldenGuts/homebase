package in_.weenja.hawidgets.core

/**
 * What the Energy dashboard is set up with (`energy/get_prefs`). Statistic ids that contain ':' are
 * external statistics with no entity behind them; they only work through the recorder statistics API.
 */
class EnergySetup(
    val gridImport: List<String>,
    val gridExport: List<String>,
    val gridPower: String?,
    val solarEnergy: List<String>,
    val solarPower: String?,
    val batteryIn: List<String>,
    val batteryOut: List<String>,
    val batteryPower: String?,
    val batterySoc: String?,
    val gas: List<String>,
    val water: List<String>,
    val devices: List<DeviceUse>,
) {
    class DeviceUse(val stat: String, val name: String?, val power: String?)

    val hasGrid get() = gridImport.isNotEmpty() || gridPower != null
    val hasSolar get() = solarEnergy.isNotEmpty() || solarPower != null
    val hasBattery get() = batteryIn.isNotEmpty() || batteryOut.isNotEmpty() || batterySoc != null || batteryPower != null
    val isEmpty get() = !hasGrid && !hasSolar && !hasBattery && gas.isEmpty() && water.isEmpty() && devices.isEmpty()

    /** Every energy statistic the widget sums for "today". */
    val todayStats: List<String> get() = (gridImport + gridExport + solarEnergy + batteryIn + batteryOut).distinct()

    /** Every entity (not external statistic) whose live state the widget reads. */
    val liveEntities: List<String>
        get() = listOfNotNull(gridPower, solarPower, batteryPower, batterySoc).plus(devices.mapNotNull { it.power }).filter { !isExternal(it) }.distinct()

    fun toJson(): Json = Json.obj("grid_import" to gridImport, "grid_export" to gridExport, "grid_power" to gridPower, "solar_energy" to solarEnergy,
        "solar_power" to solarPower, "battery_in" to batteryIn, "battery_out" to batteryOut, "battery_power" to batteryPower, "battery_soc" to batterySoc,
        "gas" to gas, "water" to water, "devices" to devices.map { Json.obj("stat" to it.stat, "name" to it.name, "power" to it.power) })

    companion object {
        fun isExternal(statId: String) = statId.contains(':')

        val EMPTY = EnergySetup(emptyList(), emptyList(), null, emptyList(), null, emptyList(), emptyList(), null, null, emptyList(), emptyList(), emptyList())

        /** Parse the `energy/get_prefs` result. Tolerates older and newer shapes of the power fields. */
        fun parse(prefs: Json?): EnergySetup {
            if (prefs == null) return EMPTY
            val gridImport = ArrayList<String>(); val gridExport = ArrayList<String>(); var gridPower: String? = null
            val solar = ArrayList<String>(); var solarPower: String? = null
            val batIn = ArrayList<String>(); val batOut = ArrayList<String>(); var batPower: String? = null; var batSoc: String? = null
            val gas = ArrayList<String>(); val water = ArrayList<String>()
            for (src in prefs.arr("energy_sources")) {
                val rates = collect(src, "stat_rate") + collect(src, "stat_power")
                when (src.str("type")) {
                    "grid" -> {
                        src.arr("flow_from").mapNotNullTo(gridImport) { it.str("stat_energy_from") }
                        src.arr("flow_to").mapNotNullTo(gridExport) { it.str("stat_energy_to") }
                        src.str("stat_energy_from")?.let { gridImport.add(it) }
                        src.str("stat_energy_to")?.let { gridExport.add(it) }
                        gridPower = gridPower ?: rates.firstOrNull()
                    }
                    "solar" -> { src.str("stat_energy_from")?.let { solar.add(it) }; solarPower = solarPower ?: rates.firstOrNull() }
                    "battery" -> {
                        src.str("stat_energy_from")?.let { batOut.add(it) }
                        src.str("stat_energy_to")?.let { batIn.add(it) }
                        batPower = batPower ?: rates.firstOrNull()
                        batSoc = batSoc ?: collect(src, "stat_soc").firstOrNull()
                    }
                    "gas" -> src.str("stat_energy_from")?.let { gas.add(it) }
                    "water" -> src.str("stat_energy_from")?.let { water.add(it) }
                }
            }
            val devices = prefs.arr("device_consumption").mapNotNull { d ->
                val stat = d.str("stat_consumption") ?: return@mapNotNull null
                DeviceUse(stat, d.str("name"), d.str("stat_rate") ?: d.str("stat_power"))
            }
            return EnergySetup(gridImport.distinct(), gridExport.distinct(), gridPower, solar.distinct(), solarPower, batIn.distinct(), batOut.distinct(),
                batPower, batSoc, gas.distinct(), water.distinct(), devices)
        }

        fun fromJson(j: Json?): EnergySetup {
            if (j == null || j !is Json.Obj) return EMPTY
            return EnergySetup(j.strings("grid_import"), j.strings("grid_export"), j.str("grid_power"), j.strings("solar_energy"), j.str("solar_power"),
                j.strings("battery_in"), j.strings("battery_out"), j.str("battery_power"), j.str("battery_soc"), j.strings("gas"), j.strings("water"),
                j.arr("devices").mapNotNull { d -> d.str("stat")?.let { DeviceUse(it, d.str("name"), d.str("power")) } })
        }

        /** Every string value stored under [key] anywhere inside [j]. */
        private fun collect(j: Json, key: String): List<String> {
            val out = ArrayList<String>()
            fun walk(x: Json) {
                when (x) {
                    is Json.Obj -> for ((k, v) in x.map) { if (k == key) v.string?.let { out.add(it) } else walk(v) }
                    is Json.Arr -> x.items.forEach { walk(it) }
                    else -> {}
                }
            }
            walk(j)
            return out
        }
    }
}

/** Today's energy totals (kWh) and live power (W) as the Energy widget shows them. */
class EnergyToday(
    val gridIn: Double?, val gridOut: Double?, val solar: Double?, val batteryIn: Double?, val batteryOut: Double?,
    val gridW: Double?, val solarW: Double?, val batteryW: Double?, val batteryPct: Double?,
) {
    /** Home consumption today = grid in + solar + battery out − grid out − battery in. */
    val home: Double?
        get() {
            if (gridIn == null && solar == null) return null
            return (gridIn ?: 0.0) + (solar ?: 0.0) + (batteryOut ?: 0.0) - (gridOut ?: 0.0) - (batteryIn ?: 0.0)
        }

    /** Live home load in W when the grid (and solar/battery) power is known. */
    val homeW: Double?
        get() = if (gridW == null && solarW == null) null else (gridW ?: 0.0) + (solarW ?: 0.0) + (batteryW ?: 0.0)

    companion object {
        /**
         * [changes]: statistic id -> kWh changed today (`recorder/statistic_during_period`, types: change).
         * [states]: live states for the power / state-of-charge entities.
         */
        fun compute(setup: EnergySetup, changes: Map<String, Double>, states: Map<String, EntityState>): EnergyToday {
            fun sum(ids: List<String>): Double? = ids.mapNotNull { changes[it] }.takeIf { it.isNotEmpty() }?.sum()
            fun watts(id: String?): Double? {
                val e = id?.let { states[it] } ?: return null
                val v = e.number ?: return null
                return when (e.unit.lowercase()) { "kw" -> v * 1000; "mw" -> v * 1_000_000; else -> v }
            }
            return EnergyToday(sum(setup.gridImport), sum(setup.gridExport), sum(setup.solarEnergy), sum(setup.batteryIn), sum(setup.batteryOut),
                watts(setup.gridPower), watts(setup.solarPower), watts(setup.batteryPower), setup.batterySoc?.let { states[it]?.number })
        }

        /** Today's totals from the synthetic [Extra.ENERGY_ID] entity plus the live power states of [home]. */
        fun of(home: Home): EnergyToday {
            val t = home[Extra.ENERGY_ID]
            val setup = home.energy
            fun watts(id: String?): Double? {
                val e = id?.let { home[it] } ?: return null
                val v = e.number ?: return null
                return when (e.unit.lowercase()) { "kw" -> v * 1000; "mw" -> v * 1_000_000; else -> v }
            }
            return EnergyToday(t?.attrDouble("grid_in"), t?.attrDouble("grid_out"), t?.attrDouble("solar"), t?.attrDouble("battery_in"), t?.attrDouble("battery_out"),
                watts(setup.gridPower), watts(setup.solarPower), watts(setup.batteryPower), setup.batterySoc?.let { home[it]?.number })
        }

        /** "1.2 kW", "640 W". */
        fun power(w: Double): String = if (kotlin.math.abs(w) >= 1000) "${Rules.fmt(w / 1000)} kW" else "${kotlin.math.round(w).toLong()} W"

        fun energy(kwh: Double): String = "${Rules.fmt(kwh)} kWh"
    }
}
