package lobos.runtime

import android.content.Context
import org.json.JSONObject

class NodeVersionManager(private val context: Context) {

    data class NodeVersion(
        val version: String,
        val channel: String,
        val minAndroidApi: Int
    )

    data class Manifest(val default: String, val abi: String, val versions: List<NodeVersion>)

    fun loadManifest(): Manifest {
        val text = context.assets.open("node-versions.json")
            .bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        val versions = json.getJSONArray("versions").let { arr ->
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                NodeVersion(
                    version = o.getString("version"),
                    channel = o.optString("channel", "unknown"),
                    minAndroidApi = o.optInt("minAndroidApi", 24)
                )
            }
        }
        return Manifest(
            default = json.getString("default"),
            abi = json.getString("abi"),
            versions = versions
        )
    }

    fun currentVersion(): String = loadManifest().default
}
